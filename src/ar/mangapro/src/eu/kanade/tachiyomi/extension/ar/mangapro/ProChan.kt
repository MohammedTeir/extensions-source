package eu.kanade.tachiyomi.extension.ar.mangapro

import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.firstInstance
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParse
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import rx.Observable
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

@Source
class ProChan(
    override val lang: String,
    override val id: Long,
) : HttpSource() {
    override val name = "ProChan"
    private val domain = "procomic.pro"
    override val baseUrl = "https://$domain"
    override val supportsLatest = true

    private val mangaTypeCache = ConcurrentHashMap<String, String>()
    private val mangaSlugCache = ConcurrentHashMap<String, String>()

    override fun headersBuilder() = super.headersBuilder()
        .set("Referer", "$baseUrl/ar/")
        .set("Origin", baseUrl)

    override fun fetchPopularManga(page: Int): Observable<MangasPage> {
        val filters = getFilterList().apply {
            firstInstance<SortFilter>().state = 2
        }
        return fetchSearchManga(page, "", filters)
    }

    override fun fetchLatestUpdates(page: Int): Observable<MangasPage> {
        // Reconstruct pages from the start of the update stream. Unlike fixed
        // batch windows, this never drops entries when an API batch contains
        // more than the remaining space on a Mihon page. Deduplicate across
        // batches, since multiple chapter updates can refer to one series.
        val start = (page - 1).coerceAtLeast(0) * PAGE_SIZE
        val end = start + PAGE_SIZE
        return fetchLatestPage(batch = 0, end = end, start = start, collected = emptyList())
    }

    private fun fetchLatestPage(
        batch: Int,
        end: Int,
        start: Int,
        collected: List<SManga>,
    ): Observable<MangasPage> = client.newCall(latestUpdatesRequest(batch + 1))
        .asObservableSuccess()
        .map(::latestUpdatesParse)
        .flatMap { result ->
            val mangas = (collected + result.mangas).distinctBy { it.url }
            if (result.hasNextPage && mangas.size < end) {
                fetchLatestPage(batch + 1, end, start, mangas)
            } else {
                Observable.just(
                    MangasPage(
                        mangas.drop(start).take(PAGE_SIZE),
                        result.hasNextPage || mangas.size > end,
                    ),
                )
            }
        }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> {
        if (query.startsWith("https://") || query.startsWith("http://")) {
            val url = query.toHttpUrl()
            if (url.host != domain && url.host != "www.$domain") {
                throw Exception("رابط غير مدعوم")
            }
            if ("chapter" in url.pathSegments) {
                throw Exception("يرجى فتح رابط السلسلة وليس رابط الفصل")
            }

            val mangaId = url.pathSegments.asReversed()
                .firstNotNullOfOrNull { segment -> ID_SUFFIX.find(segment)?.groupValues?.get(1) }
                ?: throw Exception("تعذر العثور على رقم السلسلة في الرابط")

            val manga = SManga.create().apply {
                this@apply.url = "/ar/$mangaId"
            }
            return fetchMangaDetails(manga).map { MangasPage(listOf(it), false) }
        }

        return client.newCall(searchMangaRequest(page, query, filters))
            .asObservableSuccess()
            .map { response -> searchMangaParse(response, filters) }
            .flatMap { result ->
                if (result.mangas.isEmpty() && result.hasNextPage) {
                    fetchSearchManga(page + 1, query, filters)
                } else {
                    Observable.just(result)
                }
            }
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
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

    override fun getFilterList() = FilterList(
        TypeFilter(),
        SortFilter(),
        YearFilter(),
        StatusFilter(),
        GenreFilter(),
        TagFilter(),
    )

    override fun searchMangaParse(response: Response): MangasPage = searchMangaParse(response, getFilterList())

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

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/api/public/content/latest-chapter-updates".toHttpUrl().newBuilder()
            .addQueryParameter("batch", (page - 1).coerceAtLeast(0).toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): MangasPage {
        val result = response.parseAs<LatestUpdatesPage>()
        val mangas = result.data.asSequence()
            .filter { it.type in SUPPORTED_TYPES }
            .filter { it.language == null || it.language == "AR" }
            .filterNot { it.isBlockedSeries }
            .distinctBy { it.mangaId }
            .map { update ->
                mangaTypeCache[update.mangaId.toString()] = update.type
                mangaSlugCache[update.mangaId.toString()] = update.mangaSlug
                SManga.create().apply {
                    url = mangaUrl(update.mangaSlug, update.mangaId)
                    title = update.mangaTitle
                    thumbnail_url = coverUrl(update.coverImageApp, update.coverImage)
                }
            }
            .toList()
        val hasNextPage = (result.batch + 1) * result.limit < result.total
        return MangasPage(mangas, hasNextPage)
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
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

    override fun mangaDetailsParse(response: Response): SManga {
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

    override fun chapterListRequest(manga: SManga): Request {
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

    override fun chapterListParse(response: Response): List<SChapter> {
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

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT)

    override fun pageListRequest(chapter: SChapter): Request = GET(getChapterUrl(chapter), headers)

    override fun getChapterUrl(chapter: SChapter): String = "$baseUrl${chapter.url}"

    override fun pageListParse(response: Response): List<Page> {
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
            .mapNotNull { it.desktop?.takeIf(String::isNotBlank) ?: it.mobile?.takeIf(String::isNotBlank) }
            .map(::normalizeImageUrl)
            .distinct()
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

    override fun popularMangaRequest(page: Int): Request = searchMangaRequest(page, "", getFilterList())
    override fun popularMangaParse(response: Response): MangasPage = searchMangaParse(response)
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException()

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
        return candidates.firstOrNull { !it.substringBefore('?').endsWith(".avif", ignoreCase = true) }
            ?: candidates.firstOrNull()
    }

    private fun normalizeImageUrl(url: String?): String? = url
        ?.trim()
        ?.takeIf(String::isNotEmpty)
        ?.replace("https://app.prochan.net/", "https://app.procomic.pro/")
        ?.let { if (it.startsWith("/")) "https://app.procomic.pro$it" else it }

    companion object {
        private const val PAGE_SIZE = 18
        private const val CHAPTER_PAGE_SIZE = 100
        private const val MAX_CHAPTER_PAGES = 100
        private val SUPPORTED_TYPES = setOf("manga", "manhwa", "manhua")
        private val REQUIRES_TURNSTILE_REGEX = Regex("\"requireTurnstile\":true")
        private val BLOCKED_CHAPTER_REGEX = Regex("\"blocked\":true")
        private val ID_SUFFIX = Regex("(?:^|[-/])(\\d+)(?:/|$)")
        private val APP_IMAGES_REGEX = Regex("\\\"appImages\\\"\\s*:\\s*(\\[.*?\\])", RegexOption.DOT_MATCHES_ALL)
    }
}
