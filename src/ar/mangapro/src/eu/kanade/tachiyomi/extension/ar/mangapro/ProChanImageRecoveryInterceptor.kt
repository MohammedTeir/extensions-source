package eu.kanade.tachiyomi.extension.ar.mangapro

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.logging.Logger

internal class ProChanImageRecoveryInterceptor(
    private val fallbackFor: (String) -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val primary = request.url.toString()
        val fallback = fallbackFor(primary)?.takeIf { it != primary }
            ?: return chain.proceed(request)
        val candidates = listOf(primary, fallback)
        val host = request.url.host
        var lastResponse: Response? = null
        for ((index, candidate) in candidates.withIndex()) {
            val candidateRequest = request.newBuilder().url(candidate).build()
            for (attempt in 0..MAX_IMAGE_RETRIES) {
                val response = try {
                    chain.proceed(candidateRequest)
                } catch (e: IOException) {
                    if (attempt < MAX_IMAGE_RETRIES) continue
                    if (index < candidates.lastIndex) {
                        logger.fine("ProChan image primary connection failed; attempting fallback host=$host")
                        break
                    }
                    logger.warning("ProChan image recovery failed: connection error host=$host")
                    return lastResponse ?: throw e
                }
                if (response.isSuccessful) {
                    if (index > 0) logger.fine("ProChan image fallback succeeded host=$host")
                    lastResponse?.close()
                    return response
                }
                val retryable = response.code == 429 || response.code in 500..599
                val useFallback = response.code == 403 || response.code == 404 || retryable
                if (retryable && attempt < MAX_IMAGE_RETRIES) {
                    response.close()
                    continue
                }
                if (useFallback && index < candidates.lastIndex) {
                    logger.fine("ProChan image primary HTTP ${response.code}; attempting fallback host=$host")
                    response.close()
                    break
                }
                lastResponse?.close()
                lastResponse = response
                break
            }
        }
        if (lastResponse != null) logger.warning("ProChan image recovery exhausted: HTTP ${lastResponse.code} host=$host")
        return lastResponse ?: throw IOException("Image unavailable from primary and fallback servers")
    }

    private companion object {
        const val MAX_IMAGE_RETRIES = 1
        val logger: Logger = Logger.getLogger(ProChanImageRecoveryInterceptor::class.java.name)
    }
}
