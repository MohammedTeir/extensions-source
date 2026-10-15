import eu.kanade.tachiyomi.extension.ar.mangapro.ProChanImageRecoveryInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class ProChanImageRecoveryInterceptorTest {
    private fun runScenario(primaryCodes: List<Int>, fallbackCodes: List<Int>): Triple<Int, Int, Int> {
        MockWebServer().use { primary ->
            MockWebServer().use { fallback ->
                primaryCodes.forEach { primary.enqueue(MockResponse().setResponseCode(it)) }
                fallbackCodes.forEach { fallback.enqueue(MockResponse().setResponseCode(it)) }
                val original = primary.url("/image").toString()
                val alternate = fallback.url("/image").toString()
                val client = OkHttpClient.Builder()
                    .addInterceptor(ProChanImageRecoveryInterceptor { if (it == original) alternate else null })
                    .build()
                val code = client.newCall(Request.Builder().url(original).build()).execute().use { it.code }
                return Triple(code, primary.requestCount, fallback.requestCount)
            }
        }
    }

    @Test fun successfulPrimaryDoesNotRetry() {
        assertEquals(Triple(200, 1, 0), runScenario(listOf(200), emptyList()))
    }

    @Test fun forbiddenUsesFallback() {
        assertEquals(Triple(200, 1, 1), runScenario(listOf(403), listOf(200)))
    }

    @Test fun missingUsesFallback() {
        assertEquals(Triple(200, 1, 1), runScenario(listOf(404), listOf(200)))
    }

    @Test fun rateLimitRetriesThenFallsBack() {
        assertEquals(Triple(200, 2, 1), runScenario(listOf(429, 429), listOf(200)))
    }

    @Test fun serverErrorRetriesThenFallsBack() {
        assertEquals(Triple(200, 2, 1), runScenario(listOf(503, 503), listOf(200)))
    }

    @Test fun fallbackRetriesAreBounded() {
        assertEquals(Triple(500, 2, 2), runScenario(listOf(500, 500), listOf(500, 500)))
    }

    @Test fun noFallbackLeavesRequestUnchanged() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val client = OkHttpClient.Builder().addInterceptor(ProChanImageRecoveryInterceptor { null }).build()
            val code = client.newCall(Request.Builder().url(server.url("/api")).build()).execute().use { it.code }
            assertEquals(404, code)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun connectionFailureFallsBack() {
        MockWebServer().use { primary ->
            MockWebServer().use { fallback ->
                primary.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
                primary.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))
                fallback.enqueue(MockResponse().setResponseCode(200))
                val original = primary.url("/image").toString()
                val alternate = fallback.url("/image").toString()
                val client = OkHttpClient.Builder()
                    .retryOnConnectionFailure(false)
                    .readTimeout(1, TimeUnit.SECONDS)
                    .addInterceptor(ProChanImageRecoveryInterceptor { if (it == original) alternate else null })
                    .build()
                val code = client.newCall(Request.Builder().url(original).build()).execute().use { it.code }
                assertEquals(200, code)
                assertEquals(1, fallback.requestCount)
            }
        }
    }
}
