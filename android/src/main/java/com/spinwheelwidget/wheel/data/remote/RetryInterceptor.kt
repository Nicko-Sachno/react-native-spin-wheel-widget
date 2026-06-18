package com.spinwheelwidget.wheel.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Honors the config's `retryAttempts`. OkHttp's built-in `retryOnConnectionFailure(true)` only
 * retries low-level connection drops — NOT read timeouts or 5xx responses — so to honor a real
 * retry *count* we add this application interceptor.
 *
 * Decisions made here (kept intentionally minimal for the assignment):
 *  - We retry only **transient** failures: [IOException] (timeout / connection reset) and **5xx**
 *    server errors. We do NOT retry **4xx** — a client error (bad URL, 404) won't fix itself.
 *  - [maxRetries] is the number of *extra* attempts after the first try (so retryAttempts = 3 →
 *    up to 4 total requests). retryAttempts = 0 → no retries (the loop returns the first response).
 *  - **Exponential backoff** (baseDelayMs · 2^n) so we don't hammer a struggling server. A blocking
 *    [Thread.sleep] is fine *here*: interceptors run on OkHttp's background dispatcher threads, never
 *    the main thread. (A production version might make the base/jitter configurable.)
 */
class RetryInterceptor(
    private val maxRetries: Int,
    private val baseDelayMs: Long = 500L,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        while (true) {
            try {
                val response = chain.proceed(request)
                // Success, a client error (4xx), or out of retries → return as-is.
                if (response.isSuccessful || response.code < 500 || attempt >= maxRetries) return response
                response.close() // 5xx with retries left → discard the body before retrying
            } catch (e: IOException) {
                if (attempt >= maxRetries) throw e // transient failure, but no retries left
            }
            attempt++
            try {
                Thread.sleep(baseDelayMs * (1L shl (attempt - 1))) // 500ms, 1s, 2s, …
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Retry interrupted")
            }
        }
    }
}
