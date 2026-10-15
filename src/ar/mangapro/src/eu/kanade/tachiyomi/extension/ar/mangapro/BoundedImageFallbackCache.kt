package eu.kanade.tachiyomi.extension.ar.mangapro

internal class BoundedImageFallbackCache(private val capacity: Int = 512) {
    init {
        require(capacity > 0)
    }

    private val entries = object : LinkedHashMap<String, String>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > capacity
    }

    @Synchronized
    operator fun get(url: String): String? = entries[url]

    @Synchronized
    operator fun set(url: String, fallback: String) {
        if (url != fallback) entries[url] = fallback
    }

    @Synchronized
    fun size(): Int = entries.size
}
