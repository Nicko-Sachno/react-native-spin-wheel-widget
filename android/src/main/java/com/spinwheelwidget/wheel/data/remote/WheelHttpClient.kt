package com.spinwheelwidget.wheel.data.remote

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Single shared OkHttp client for the widget. One instance owns the connection pool and dispatcher,
 * so it must be reused (creating a client per request throws away pooling and leaks threads).
 */
object WheelHttpClient {

    // Bootstrap client: conservative constant timeouts, used as-is for the config fetch (the config
    // — and thus its network policy — isn't known yet at that point). No retry here on purpose: the
    // single config call already degrades gracefully via the cache → bundled fallback chain.
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Client tuned for the *asset* downloads, once the config is parsed. `newBuilder()` SHARES the
     * base connection pool/dispatcher and only overrides what we set — so it's cheap and we still
     * have exactly one pool. Applies the config's networkTimeoutMs and retryAttempts.
     */
    fun configuredFor(attributes: Attributes): OkHttpClient =
        client.newBuilder()
            .callTimeout(attributes.networkTimeoutMs, TimeUnit.MILLISECONDS)
            .addInterceptor(RetryInterceptor(attributes.retryAttempts))
            .build()
}
