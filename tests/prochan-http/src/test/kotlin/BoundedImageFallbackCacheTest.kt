import eu.kanade.tachiyomi.extension.ar.mangapro.BoundedImageFallbackCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

class BoundedImageFallbackCacheTest {
    @Test fun evictsLeastRecentlyUsed() {
        val cache = BoundedImageFallbackCache(2)
        cache["a"] = "fallback-a"
        cache["b"] = "fallback-b"
        assertEquals("fallback-a", cache["a"])
        cache["c"] = "fallback-c"
        assertNull(cache["b"])
        assertEquals("fallback-a", cache["a"])
        assertEquals("fallback-c", cache["c"])
        assertEquals(2, cache.size())
    }

    @Test fun ignoresSelfFallbacks() {
        val cache = BoundedImageFallbackCache(2)
        cache["a"] = "a"
        assertEquals(0, cache.size())
    }

    @Test fun concurrentWritesStayBounded() {
        val cache = BoundedImageFallbackCache(32)
        val start = CountDownLatch(1)
        val workers = (0 until 8).map { worker ->
            thread {
                start.await()
                repeat(1000) { index ->
                    val key = "$worker-$index"
                    cache[key] = "backup-$key"
                    cache[key]
                }
            }
        }
        start.countDown()
        workers.forEach { it.join() }
        assertEquals(32, cache.size())
    }
}
