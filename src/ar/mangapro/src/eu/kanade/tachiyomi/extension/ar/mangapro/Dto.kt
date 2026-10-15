package eu.kanade.tachiyomi.extension.ar.mangapro

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.contentOrNull

@Serializable
class ContentPage(
    val data: List<Content>,
    val meta: PageMeta = PageMeta(),
)

@Serializable
class PageMeta(
    val pages: Int = 1,
    val page: Int = 1,
)

@Serializable
class Content(
    val id: Int,
    val title: String,
    val slug: String,
    val type: String,
    val description: String? = null,
    val progress: String? = null,
    val status: String? = null,
    val thumbnail: String? = null,
    @SerialName("cdn_path")
    val cdnPath: String? = null,
    val metadata: ContentMetadata = ContentMetadata(),
    val coverImageApp: CoverImage? = null,
)

@Serializable
class ContentMetadata(
    val genres: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val originalTitle: String? = null,
    val altTitles: List<String> = emptyList(),
    @Serializable(with = StringListSerializer::class)
    val author: List<String> = emptyList(),
    @Serializable(with = StringListSerializer::class)
    val artist: List<String> = emptyList(),
    val year: String? = null,
    val origin: String? = null,
    val coverImage: String? = null,
    val coverImageApp: CoverImage? = null,
)

@Serializable
class CoverImage(
    val mobile: String? = null,
    val desktop: String? = null,
    val card: JsonElement? = null,
)

@Serializable
class LatestComicsPage(
    val data: List<LatestComicsSeries>,
    val meta: LatestComicsMeta = LatestComicsMeta(),
)

@Serializable
class LatestComicsMeta(
    val page: Int = 1,
    val limit: Int = 18,
    val category: String = "comics",
    val hasMore: Boolean = false,
)

@Serializable
class LatestComicsSeries(
    val mangaId: Int,
    val mangaSlug: String,
    val mangaTitle: String,
    val coverImage: String? = null,
    val coverImageApp: CoverImage? = null,
    val type: String,
    val isBlockedSeries: Boolean = false,
    val chapters: List<LatestComicsChapter> = emptyList(),
)

@Serializable
class LatestComicsChapter(
    val language: String? = null,
)

@Serializable
class LatestUpdatesPage(
    val data: List<LatestChapterUpdate>,
    val total: Int = 0,
    val limit: Int = 18,
    val batch: Int = 0,
)

@Serializable
class LatestChapterUpdate(
    val id: Int,
    val mangaId: Int,
    val mangaSlug: String,
    val mangaTitle: String,
    val coverImage: String? = null,
    val coverImageApp: CoverImage? = null,
    val type: String,
    val language: String? = null,
    val chapterNumber: String? = null,
    val isBlockedSeries: Boolean = false,
)

@Serializable
class ChapterList(
    val data: List<Chapter>,
    val total: Int = 0,
)

@Serializable
class Chapter(
    val id: Int,
    @SerialName("chapter_number")
    val number: String,
    val language: String? = null,
    val title: String? = null,
    @SerialName("uploader_nickname")
    val uploader: String? = null,
    val translator: String? = null,
    @SerialName("coins_unlocks")
    val coins: Int? = null,
    @SerialName("shortlink_unlocks")
    val shortlinkUnlocks: Int? = null,
    val lockedForever: Boolean = false,
    val lockedByCoins: Boolean = false,
    val lockedByShortlink: Boolean = false,
    val lockedByExclusive: Boolean = false,
    @SerialName("published_at")
    val publishedAt: String? = null,
    @SerialName("created_at")
    val createdAt: String? = null,
)

@Serializable
class ChapterPageImage(
    val mobile: String? = null,
    val desktop: String? = null,
)

object StringListSerializer : JsonTransformingSerializer<List<String>>(ListSerializer(String.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement = when {
        element is JsonNull -> JsonArray(emptyList())
        element is JsonPrimitive -> {
            val elements = element.contentOrNull
                ?.split("\n")
                ?.map { JsonPrimitive(it.trim()) }
                .orEmpty()
            JsonArray(elements)
        }
        else -> element
    }
}
