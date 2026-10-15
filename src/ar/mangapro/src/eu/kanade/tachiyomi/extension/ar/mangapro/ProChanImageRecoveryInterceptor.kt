package eu.kanade.tachiyomi.extension.ar.mangapro

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

internal class ProChanImageRecoveryInterceptor(
    private val fallbackFor: (String) -> String?,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val primary = request.url.toString()
        val fallback = fallbackFor(primary)?.takeIf { it != primary }
            ?: return chain.proceed(request)
        val candidates = listOf(primary, fallback)
        var lastResponse: Response? = null
        for ((index, candidate) in candidates.withIndex()) {
            val candidateRequest = request.newBuilder().url(candidate).build()
            for (attempt in 0..MAX_IMAGE_RETRIES) {
                val response = try {
                    chain.proceed(candidateRequest)
                } catch (e: IOException) {
                    if (attempt < MAX_IMAGE_RETRIES) continue
                    if (index < candidates.lastIndex) break
                    return lastResponse ?: throw e
                }
                if (response.isSuccessful) {
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
                    response.close()
                    break
                }
                lastResponse?.close()
                lastResponse = response
                break
            }
        }
        return lastResponse ?: throw IOException("Image unavailable from primary and fallback servers")
    }

    private companion object {
        const val MAX_IMAGE_RETRIES = 1
    }
}
