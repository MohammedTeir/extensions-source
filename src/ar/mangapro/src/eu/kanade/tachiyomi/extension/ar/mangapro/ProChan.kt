package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.source.KeiSource
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

@Source
abstract class ProChan : KeiSource() {
    private val domain = "procomic.pro"

    private val mangaTypeCache = ConcurrentHashMap<String, String>()
    private val mangaSlugCache = ConcurrentHashMap<String, String>()
    private val imageFallbacks = BoundedImageFallbackCache()
    private val searchGeneration = java.util.concurrent.atomic.AtomicLong()

    override fun OkHttpClient.Builder.configureClient() = addInterceptor(ProChanImageRecoveryInterceptor { imageFallbacks[it] })

    override fun Headers.Builder.configureHeaders() = set("Referer", "$baseUrl/ar/")

    private suspend fun <T> requestParsed(request: Request, parse: (Response) -> T): T = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            parse(response)
        }
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val filters = getFilterList().apply { firstInstance<SortFilter>().state = 2 }
        return requestParsed(searchMangaRequest(page, "", filters)) { searchMangaParse(it, filters) }
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage = requestParsed(latestUpdatesRequest(page), ::latestUpdatesParse)

    override suspend fun getMangaByUrl(url: HttpUrl): SManga {
        if (url.host != domain && url.host != "www.$domain") throw Exception("رابط غير مدعوم")
        if ("chapter" in url.pathSegments) throw Exception("يرجى فتح رابط السلسلة وليس رابط الفصل")
        val mangaId = url.pathSegments.asReversed()
            .firstNotNullOfOrNull { segment -> ID_SUFFIX.find(segment)?.groupValues?.get(1) }
            ?: throw Exception("تعذر العثور على رقم السلسلة في الرابط")
        return requestParsed(mangaDetailsRequest(SManga.create().apply { this.url = "/ar/$mangaId" }), ::mangaDetailsParse)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage = withContext(Dispatchers.IO) {
        val start = (page - 1).coerceAtLeast(0) * PAGE_SIZE
        val key = buildString {
            append(query, '|', searchMangaRequest(1, query, filters).url)
            append('|', filters.firstInstance<StatusFilter>().selected)
            val genres = filters.firstInstance<GenreFilter>()
            val tags = filters.firstInstance<TagFilter>()
            append('|', genres.included.sorted(), '|', genres.excluded.sorted())
            append('|', tags.included.sorted(), '|', tags.excluded.sorted())
        }
        val now = System.currentTimeMillis()
        val cached = searchSnapshot?.takeIf {
            page > 1 && it.key == key && now - it.createdAt in 0 until SEARCH_CACHE_MILLIS
        }
        val generation = searchGeneration.incrementAndGet()
        val snapshot = cached ?: SearchSnapshot(key, emptyList(), 1, true, now)
        fetchFilteredSearchPage(snapshot, start, start + PAGE_SIZE, query, filters, generation)
    }

    private class SearchSnapshot(
        val key: String,
        val mangas: List<SManga>,
        val nextPage: Int,
        val hasMore: Boolean,
        val createdAt: Long,
    )

    @Volatile
    private var searchSnapshot: SearchSnapshot? = null

    private suspend fun fetchFilteredSearchPage(
        snapshot: SearchSnapshot,
        start: Int,
        end: Int,
        query: String,
        filters: FilterList,
        generation: Long,
    ): MangasPage {
        var current = snapshot
        while (current.mangas.size < end && current.hasMore && current.nextPage <= MAX_SEARCH_PAGES) {
            val result = requestParsed(searchMangaRequest(current.nextPage, query, filters)) {
                searchMangaParse(it, filters)
            }
            current = SearchSnapshot(
                current.key,
                (current.mangas + result.mangas).distinctBy { it.url },
                current.nextPage + 1,
                result.hasNextPage,
                current.createdAt,
            )
            if (searchGeneration.get() == generation) searchSnapshot = current
        }
        return MangasPage(
            current.mangas.drop(start).take(PAGE_SIZE),
            (current.hasMore && current.nextPage <= MAX_SEARCH_PAGES) || current.mangas.size > end,
        )
    }

    fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val sort = when (filters.firstInstance<SortFilter>().selected) {
            "popular", "total_popularity" -> "popular"
            "oldest" -> "oldest"
            "az" -> "az"
            "za" -> "za"
            else -> "new"
        }
        val url = "$baseUrl/api/content".toHttpUrl().newBuilder()
            .addQueryParameter("status", "approved")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("sort", sort)
            .apply {
                query.takeIf(String::isNotBlank)?.also { addQueryParameter("search", it) }
                filters.firstInstance<TypeFilter>().selected?.also { addQueryParameter("type", it) }
                filters.firstInstance<YearFilter>().selected?.also { addQueryParameter("year", it) }
            }
            .build()
        return GET(url, headers)
    }

    override fun getFilterList(data: JsonElement?) = FilterList(
        TypeFilter(),
        SortFilter(),
        YearFilter(),
        StatusFilter(),
        GenreFilter(),
        TagFilter(),
    )

    fun searchMangaParse(response: Response): MangasPage = searchMangaParse(response, getFilterList())

    private fun searchMangaParse(response: Response, filters: FilterList): MangasPage {
        val result = response.parseAs<ContentPage>()
        val statusFilter = filters.firstInstance<StatusFilter>().selected
        val yearFilter = filters.firstInstance<YearFilter>().selected
        val genreFilter = filters.firstInstance<GenreFilter>()
        val tagFilter = filters.firstInstance<TagFilter>()

        val mangas = result.data.asSequence()
            .filter { it.type in SUPPORTED_TYPES }
            .filter { statusFilter == null || it.progress == statusFilter }
            .filter { yearFilter == null || it.metadata.year == yearFilter }
            .filter { genreFilter.included.isEmpty() || it.metadata.genres.containsAll(genreFilter.included) }
            .filter { genreFilter.excluded.none { excluded -> excluded in it.metadata.genres } }
            .filter { tagFilter.included.isEmpty() || it.metadata.tags.containsAll(tagFilter.included) }
            .filter { tagFilter.excluded.none { excluded -> excluded in it.metadata.tags } }
            .map(::toSManga)
            .toList()

        return MangasPage(mangas, result.meta.pages > result.meta.page)
    }

    fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/public/content/latest-updates".toHttpUrl().newBuilder()
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("page", page.coerceAtLeast(1).toString())
            .addQueryParameter("category", "comics")
            .build()
        return GET(url, headers)
    }

    fun latestUpdatesParse(response: Response): MangasPage {
        val result = response.parseAs<LatestComicsPage>()
        val mangas = result.data.asSequence()
            .filter { it.type.lowercase(Locale.ROOT) in SUPPORTED_TYPES }
            .filterNot { it.isBlockedSeries }
            .filter { series -> series.chapters.any { it.language.equals("AR", ignoreCase = true) } }
            .distinctBy { it.mangaId }
            .map { series ->
                mangaTypeCache[series.mangaId.toString()] = series.type
                mangaSlugCache[series.mangaId.toString()] = series.mangaSlug
                SManga.create().apply {
                    url = mangaUrl(series.mangaSlug, series.mangaId)
                    title = series.mangaTitle
                    thumbnail_url = coverUrl(series.coverImageApp, series.coverImage)
                }
            }
            .toList()
        return MangasPage(mangas, result.meta.hasMore)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = withContext(Dispatchers.IO) {
        val updatedManga = if (fetchDetails) requestParsed(mangaDetailsRequest(manga), ::mangaDetailsParse) else manga
        val updatedChapters = if (fetchChapters) requestParsed(chapterListRequest(updatedManga), ::chapterListParse) else chapters
        SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> = requestParsed(pageListRequest(chapter), ::pageListParse)

    fun mangaDetailsRequest(manga: SManga): Request {
        val id = mangaId(manga.url)
        return GET("$baseUrl/api/content/$id", headers)
    }

    override fun getMangaUrl(manga: SManga): String {
        val id = mangaId(manga.url)
        val path = manga.url.substringBefore('?')
        if (path.startsWith("/ar/")) return "$baseUrl$path"
        val lastSegment = path.trimEnd('/').substringAfterLast('/')
        val slug = lastSegment.takeIf { ID_SUFFIX.find(it) == null }
            ?: mangaSlugCache[id]
            ?: id
        return "$baseUrl/ar/$slug-$id"
    }

    fun mangaDetailsParse(response: Response): SManga {
        val manga = response.parseAs<Content>()
        mangaTypeCache[manga.id.toString()] = manga.type
        mangaSlugCache[manga.id.toString()] = manga.slug

        return SManga.create().apply {
            url = mangaUrl(manga.slug, manga.id)
            title = manga.title
            author = manga.metadata.author.joinToString()
            artist = manga.metadata.artist.joinToString()
            description = buildString {
                manga.description?.trim()?.takeIf(String::isNotEmpty)?.also { append(it, "\n\n") }
                buildList {
                    addAll(manga.metadata.altTitles)
                    manga.metadata.originalTitle?.also { add(it) }
                }.takeIf { it.isNotEmpty() }?.also { titles ->
                    append("عناوين بديلة\n")
                    titles.forEach { append("- ", it, "\n") }
                }
            }.trim()
            genre = buildList {
                add(manga.type.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                manga.metadata.year?.also(::add)
                manga.metadata.origin?.also { origin ->
                    add(origin.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() })
                }
                when (manga.type) {
                    "manga" -> add("مانجا")
                    "manhwa" -> add("مانهوا")
                    "manhua" -> add("مانها")
                }
                addAll(manga.metadata.genres)
                addAll(manga.metadata.tags)
            }.distinct().joinToString()
            status = when (manga.progress?.trim()) {
                "مستمر" -> SManga.ONGOING
                "مكتمل" -> SManga.COMPLETED
                "متوقف" -> SManga.ON_HIATUS
                else -> SManga.UNKNOWN
            }
            thumbnail_url = coverUrl(manga.metadata.coverImageApp ?: manga.coverImageApp, manga.metadata.coverImage, manga.thumbnail)
            initialized = true
        }
    }

    fun chapterListRequest(manga: SManga): Request {
        val id = mangaId(manga.url)
        val type = mangaTypeCache[id] ?: lookupMangaType(id)
        mangaSlugCache.putIfAbsent(id, manga.url.substringBefore('?').trimEnd('/').substringAfterLast('/').takeIf { ID_SUFFIX.find(it) == null } ?: id)
        val url = "$baseUrl/api/public/$type/$id/chapters".toHttpUrl().newBuilder()
            .addQueryParameter("page", "1")
            .addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
            .addQueryParameter("order", "desc")
            .build()
        return GET(url, headers)
    }

    fun chapterListParse(response: Response): List<SChapter> {
        val requestUrl = response.request.url
        val type = requestUrl.pathSegments[2]
        val id = requestUrl.pathSegments[3]
        val slug = response.request.url.queryParameter("slug")
            ?: response.request.url.encodedPathSegments.lastOrNull().orEmpty()

        val result = response.parseAs<ChapterList>()
        val chapters = result.data.toMutableList()
        val seenChapterIds = chapters.mapTo(mutableSetOf()) { it.id }
        var page = 2
        while (chapters.size < result.total && page <= MAX_CHAPTER_PAGES) {
            val nextUrl = "$baseUrl/api/public/$type/$id/chapters".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .addQueryParameter("limit", CHAPTER_PAGE_SIZE.toString())
                .addQueryParameter("order", "desc")
                .build()
            val next = client.newCall(GET(nextUrl, headers)).execute().use { nextResponse ->
                if (!nextResponse.isSuccessful) throw Exception("HTTP ${nextResponse.code}")
                nextResponse.parseAs<ChapterList>()
            }
            if (next.data.isEmpty()) break
            val unseen = next.data.filter { seenChapterIds.add(it.id) }
            if (unseen.isEmpty()) break
            chapters.addAll(unseen)
            page++
        }

        val manga = client.newCall(GET("$baseUrl/api/content/$id", headers)).execute().use { detailResponse ->
            if (!detailResponse.isSuccessful) throw Exception("HTTP ${detailResponse.code}")
            detailResponse.parseAs<Content>()
        }
        val mangaSlug = manga.slug.ifBlank { slug }
        mangaTypeCache[id] = manga.type
        mangaSlugCache[id] = mangaSlug

        return chapters.asSequence()
            .filter { it.language == "AR" }
            .map { chapter ->
                val number = chapter.number.trim()
                SChapter.create().apply {
                    url = "/ar/chapter/$mangaSlug-$number-${chapter.id}"
                    name = buildString {
                        append("\u200Fالفصل ", number)
                        chapter.title?.trim()?.takeIf { it.isNotBlank() && it != number }?.let {
                            append(" - ", it)
                        }
                        if (chapter.lockedForever || chapter.lockedByCoins || chapter.lockedByExclusive || chapter.lockedByShortlink) {
                            append(" 🔒")
                        }
                    }
                    scanlator = chapter.uploader?.takeIf(String::isNotBlank)
                        ?: chapter.translator?.takeIf(String::isNotBlank)
                        ?: "\u200B"
                    chapter_number = number.toFloatOrNull() ?: -1f
                    date_upload = (chapter.publishedAt ?: chapter.createdAt)?.let(dateFormat::tryParse) ?: 0L
                }
            }
            .sortedByDescending { it.chapter_number }
            .toList()
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
    }

    fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    fun pageListParse(response: Response): List<Page> {
        when (response.code) {
            401, 403 -> throw Exception("رفض الموقع الوصول إلى الفصل؛ افتحه في WebView للتحقق من الجلسة")
            404 -> throw Exception("الفصل غير موجود أو تم حذفه من الموقع")
            429 -> throw Exception("طلبات كثيرة للموقع؛ يرجى المحاولة لاحقًا")
        }
        if (!response.isSuccessful) throw Exception("تعذر تحميل الفصل (HTTP ${response.code})")
        val body = response.body.string().replace("\\\"", "\"")
        if (REQUIRES_TURNSTILE_REGEX.containsMatchIn(body)) {
            throw Exception("يتطلب الموقع إكمال التحقق قبل فتح الفصل")
        }
        if (BLOCKED_CHAPTER_REGEX.containsMatchIn(body)) {
            throw Exception("هذا الفصل مقفل على الموقع")
        }
        val imagesJson = APP_IMAGES_REGEX.find(body)?.groupValues?.get(1)
            ?: throw Exception("تعذر قراءة صور الفصل")
        val images = imagesJson.parseAs<List<ChapterPageImage>>()
            .mapNotNull { image ->
                val desktop = normalizeImageUrl(image.desktop)
                val mobile = normalizeImageUrl(image.mobile)
                if (desktop != null && mobile != null && desktop != mobile) {
                    imageFallbacks[desktop] = mobile
                }
                desktop ?: mobile
            }
        if (images.isEmpty()) throw Exception("لا توجد صور متاحة لهذا الفصل")

        val chapterUrl = response.request.url.toString()
        return images.mapIndexed { index, imageUrl -> Page(index, chapterUrl, imageUrl) }
    }

    override fun imageRequest(page: Page): Request {
        val imageHeaders = headersBuilder()
            .set("Referer", page.url)
            .build()
        return GET(page.imageUrl!!, imageHeaders)
    }

    fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", getFilterList())
    fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)
    fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

    private fun toSManga(manga: Content): SManga {
        mangaTypeCache[manga.id.toString()] = manga.type
        mangaSlugCache[manga.id.toString()] = manga.slug
        return SManga.create().apply {
            url = mangaUrl(manga.slug, manga.id)
            title = manga.title
            thumbnail_url = coverUrl(manga.coverImageApp, manga.thumbnail, manga.metadata.coverImage)
        }
    }

    private fun mangaUrl(slug: String, id: Int): String = "/ar/$slug-$id"

    private fun mangaId(url: String): String = ID_SUFFIX.findAll(url).lastOrNull()?.groupValues?.get(1)
        ?: throw Exception("تعذر العثور على رقم السلسلة")

    private fun lookupMangaType(id: String): String {
        val content = client.newCall(GET("$baseUrl/api/content/$id", headers)).execute().use { response ->
            if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
            response.parseAs<Content>()
        }
        if (content.type !in SUPPORTED_TYPES) throw Exception("نوع غير مدعوم")
        mangaTypeCache[id] = content.type
        mangaSlugCache[id] = content.slug
        return content.type
    }

    private fun coverUrl(cover: CoverImage?, vararg fallbacks: String?): String? {
        val card = cover?.card
        val cardUrls = when (card) {
            is JsonObject -> listOf(
                (card["mobile"] as? JsonPrimitive)?.contentOrNull,
                (card["desktop"] as? JsonPrimitive)?.contentOrNull,
            )
            is JsonPrimitive -> listOf(card.contentOrNull)
            else -> emptyList()
        }
        val candidates = (cardUrls + listOf(cover?.mobile, cover?.desktop) + fallbacks.toList())
            .mapNotNull(::normalizeImageUrl)
            .distinct()
        // Prefer conventional cover formats over AVIF, which can fail to decode
        // on some Android image-loader/device combinations.
        val primary = candidates.firstOrNull { !it.substringBefore('?').endsWith(".avif", ignoreCase = true) }
            ?: candidates.firstOrNull()
        if (primary != null) {
            candidates.firstOrNull { it != primary }?.let { imageFallbacks[primary] = it }
        }
        return primary
    }

    private fun normalizeImageUrl(url: String?): String? = url
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.replace(LEGACY_IMAGE_HOST_REGEX, "https://app.procomic.pro/")
        ?.let { value ->
            when {
                value.startsWith("//") -> "https:$value"
                value.startsWith("/") -> "https://app.procomic.pro$value"
                value.startsWith("https://") || value.startsWith("http://") -> value
                else -> null
            }
        }

    companion object {
        private val logger = Logger.getLogger(ProChan::class.java.name)
        private val LEGACY_IMAGE_HOST_REGEX = Regex("^https?://app\\.prochan\\.net/", RegexOption.IGNORE_CASE)
        private const val MAX_IMAGE_RETRIES = 1
        private const val PAGE_SIZE = 18
        private const val MAX_SEARCH_PAGES = 100
        private const val SEARCH_CACHE_MILLIS = 120_000L
        private const val CHAPTER_PAGE_SIZE = 100
        private const val MAX_CHAPTER_PAGES = 100
        private val SUPPORTED_TYPES = setOf("manga", "manhwa", "manhua")
        private val REQUIRES_TURNSTILE_REGEX = Regex("\"requireTurnstile\":true")
        private val BLOCKED_CHAPTER_REGEX = Regex("\"blocked\":true")
        private val ID_SUFFIX = Regex("(?:^|[-/])(\\d+)(?:/|$)")
        private val APP_IMAGES_REGEX = Regex("\"appImages\"\\s*:\\s*(\\[.*?\\])", RegexOption.DOT_MATCHES_ALL)
    }
}
