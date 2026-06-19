package com.spinwheelwidget.wheel

import android.content.Context
import android.util.Log
import com.spinwheelwidget.wheel.data.WheelSerializers
import com.spinwheelwidget.wheel.data.cache.WheelCache
import com.spinwheelwidget.wheel.data.remote.ConfigResponse
import com.spinwheelwidget.wheel.data.remote.Data
import com.spinwheelwidget.wheel.data.remote.WheelHttpClient
import okhttp3.Request
import java.io.File

/**
 * How stale the cached config is, derived from its age against the two TTLs in the config:
 *  - FRESH (age < refreshIntervalSec): inside the soft TTL → serve cache, skip the network.
 *  - STALE (refreshIntervalSec ≤ age < cacheExpirationSec): soft TTL passed but still usable →
 *            serve cache immediately AND revalidate (stale-while-revalidate). If the network fails,
 *            the cache is still trusted as the fallback (stale-if-error).
 *  - EXPIRED (age ≥ cacheExpirationSec, or no cache at all): hard TTL passed → the cache is no
 *            longer trusted. We must revalidate; if the network fails, we fall back to the bundled
 *            baseline rather than the expired cache, so we never render data past its hard limit
 *            (and never go blank either).
 */
enum class Freshness { FRESH, STALE, EXPIRED }

/** Instant, local-only view of the config for the first paint (no network). */
data class Snapshot(
    val config: Data,
    val freshness: Freshness,
    /** True when there was no cache yet and we fell back to the bundled config. */
    val placeholder: Boolean,
)

/**
 * Orchestrates config and asset loading. It owns the *policy* (when to hit the network, what to fall
 * back to); the mechanisms it drives are dumb: [WheelHttpClient] (transport), [WheelSerializers]
 * (parsing), [WheelCache] (disk + prefs).
 *
 * Caching model: the cache is the single source of truth for rendering, with the bundled config as
 * a built-in zeroth tier so the widget is never blank. Refresh is cache-first with a two-tier TTL
 * (soft = refreshIntervalSec, hard = cacheExpirationSec) and stale-while-revalidate.
 *
 * Threading: every public method here does blocking I/O — call them from a background dispatcher,
 * never the main thread (OkHttp would throw NetworkOnMainThreadException).
 */
class WheelRepository(context: Context) {

    private val appContext = context.applicationContext
    private val cache = WheelCache(appContext)


    /**
     * Instant local read for the first paint. Reads the CBOR cache (no network); if nothing is
     * cached yet, seeds and returns the bundled config. Always returns something → never blank.
     */
    fun snapshot(): Snapshot {
        overrideConfig()?.let { return Snapshot(it.activeConfig(), Freshness.FRESH, placeholder = false) }
        val cached = cache.readConfig()
        return if (cached != null) {
            Snapshot(cached.activeConfig(), freshnessOf(cached), placeholder = false)
        } else {
            // First run: no cache. Seed from the bundled config so the next snapshot is instant.
            val bundled = readBundledConfig().also { cache.saveConfig(it) }
            Snapshot(bundled.activeConfig(), Freshness.EXPIRED, placeholder = true)
        }
    }

    /**
     * Network-aware resolve honoring both TTLs. Returns the config to render and updates the cache
     * as a side effect. Self-throttling: if the cache is still FRESH it returns it without any
     * network call, so it's safe for the widget to always call this on a background wake.
     */
    fun refresh(): Data = resolve().activeConfig()

    /**
     * Best-effort download of the four wheel images into the local asset cache. Skips entirely when
     * the host is blank or the sample placeholder, and skips any asset already cached.
     */
    fun cacheAssets(config: Data) {
        // assetsHostOverride (from configure()) wins; otherwise use the host declared in the config.
        val host = assetsHostOverride.ifBlank { config.network.assets.host }
        if (host.isBlank() || host.contains(PLACEHOLDER_HOST_MARKER)) return

        val client = WheelHttpClient.configuredFor(config.network.attributes)
        val relatives = with(config.wheel.assets) { listOf(bg, wheelFrame, wheelSpin, wheel) }

        relatives.forEach { relative ->
            // Within a single config, skip-if-already-cached is safe: lh3 addresses by immutable
            // Drive file ID, so the same relative always maps to the same bytes.
            if (cache.hasAsset(relative)) return@forEach
            runCatching {
                val url = host.trimEnd('/') + "/" + relative.trimStart('/')
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    val bytes = resp.body?.bytes()
                    if (resp.isSuccessful && bytes != null) cache.saveAsset(relative, bytes)
                }
            }.onFailure { Log.w(TAG, "Asset download failed for $relative: ${it.message}") }
        }

        // Reconcile across configs: delete any cached file NOT referenced by this config (orphans
        // left over when a previous config pointed at different asset IDs). `keep` is built from
        // assetFile(it).name so the names match exactly how saveAsset stored them on disk. Net
        // effect: the asset cache now mirrors this config — new IDs downloaded above, stale IDs
        // pruned here, unchanged ones survive (their name is in `keep`).
        cache.pruneAssetsExcept(relatives.map { cache.assetFile(it).name }.toSet())
    }

    /** The sector under the pointer for the persisted resting angle (geometric index only — no label). */
    fun lastResult(): SpinResult {
        val angle = restingAngle
        return SpinResult(segmentAt(angle, SEGMENT_COUNT), angle)
    }

    /**
     * The cached FILE for a config-relative asset, or null if it hasn't been downloaded yet. Note:
     * distinct from [cacheAssets] (which DOWNLOADS all assets) — this only returns the path of one
     * already-cached file. The repo owns the cache, so it exposes the File; the UI layer decodes it.
     */
    fun cachedAssetFile(relative: String): File? = cache.assetFile(relative).takeIf { it.exists() }

    // Persisted state, delegated to the cache.
    var restingAngle: Float
        get() = cache.restingAngle
        set(value) { cache.restingAngle = value }

    val lastFetchTime: Long get() = cache.lastConfigFetchTime

    /**
     * Force the next [refresh] to re-fetch the remote config, bypassing the soft-TTL throttle. Zeroes
     * the persisted last-fetch time so [freshnessOf] reports EXPIRED. The RN bridge calls this on an
     * explicit refresh() so an operator who just updated the hosted config sees it immediately, rather
     * than waiting up to refreshInterval. (If the forced fetch fails offline, EXPIRED falls back to the
     * bundled baseline.)
     */
    fun forceNextRefresh() { cache.lastConfigFetchTime = 0L }

    // Runtime config written by the RN bridge (configure()/setConfigJson()); delegated to prefs.
    var configUrl: String
        get() = cache.configUrl
        set(value) { cache.configUrl = value }

    var assetsHostOverride: String
        get() = cache.assetsHostOverride
        set(value) { cache.assetsHostOverride = value }

    var rawConfigOverride: String?
        get() = cache.rawConfigOverride
        set(value) { cache.rawConfigOverride = value }

    // ---- TTL policy ------------------------------------------------------

    private fun freshnessOf(cached: ConfigResponse?): Freshness {
        if (cached == null) return Freshness.EXPIRED // nothing cached → must fetch
        val attrs = cached.activeConfig().network.attributes
        val age = System.currentTimeMillis() - cache.lastConfigFetchTime
        return when {
            age < attrs.refreshIntervalSec * 1000 -> Freshness.FRESH    // within soft TTL
            age < attrs.cacheExpirationSec * 1000 -> Freshness.STALE    // soft passed, hard not yet
            else -> Freshness.EXPIRED                                   // past hard TTL
        }
    }

    private fun resolve(): ConfigResponse {
        overrideConfig()?.let { return it } // explicit JSON override (setConfigJson) wins — offline, authoritative
        val cached = cache.readConfig()
        return when (freshnessOf(cached)) {
            // FRESH: throttle — serve cache, no network. (cached is non-null when FRESH.)
            Freshness.FRESH -> cached!!

            // STALE: revalidate, but the cache is still trusted as the fallback (stale-if-error).
            Freshness.STALE -> fetchAndCache() ?: cached ?: seedFromBundled()

            // EXPIRED: must revalidate. On success we serve fresh data. On failure (offline) we apply
            // stale-if-error: prefer the expired-but-real cache over the bundled baseline, and — key —
            // we do NOT overwrite it, so the last-known-good remote config (e.g. the live promotion +
            // its assets) survives until the network returns and we can fetch fresh. Only when there is
            // no cache at all do we seed from bundled. The widget never goes blank either way (bundled
            // drawables back the assets). Rationale: reverting a live promo to a baked-in default the
            // moment a device goes offline past the hard TTL is worse UX than briefly showing stale-but-
            // real content; see the README's "Design decisions" section.
            Freshness.EXPIRED -> fetchAndCache() ?: cached ?: seedFromBundled()
        }
    }

    /** Fetch + parse + cache the remote config. Returns null on any failure (offline, HTTP error). */
    private fun fetchAndCache(): ConfigResponse? {
        val url = configUrl
        if (url.isBlank()) return null // no endpoint configured → fully offline
        return runCatching { fetchRemoteConfig(url) }
            .onSuccess { remote ->
                cache.saveConfig(remote)
                cache.lastConfigFetchTime = System.currentTimeMillis() // "fetch" = remote success
            }
            .onFailure { Log.w(TAG, "Remote config fetch failed: ${it.message}") }
            .getOrNull()
    }

    private fun fetchRemoteConfig(url: String): ConfigResponse {
        val request = Request.Builder().url(url).build()
        WheelHttpClient.client.newCall(request).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            require(resp.isSuccessful && body.isNotBlank()) { "HTTP ${resp.code}" }
            return WheelSerializers.json.decodeFromString(ConfigResponse.serializer(), body)
        }
    }

    /** Parse the bundled asset config and seed the cache. Does NOT stamp lastFetchTime (not a fetch). */
    private fun seedFromBundled(): ConfigResponse = readBundledConfig().also { cache.saveConfig(it) }

    private fun readBundledConfig(): ConfigResponse =
        appContext.assets.open(BUNDLED_CONFIG).bufferedReader().use {
            WheelSerializers.json.decodeFromString(ConfigResponse.serializer(), it.readText())
        }

    /** The single widget config the wheel uses */
    private fun ConfigResponse.activeConfig(): Data = data.firstOrNull() ?: Data()

    /** Parse the JSON pushed via setConfigJson(), if any. Highest-priority, fully-offline override. */
    private fun overrideConfig(): ConfigResponse? =
        rawConfigOverride?.takeIf { it.isNotBlank() }?.let { json ->
            runCatching { WheelSerializers.json.decodeFromString(ConfigResponse.serializer(), json) }
                .onFailure { Log.w(TAG, "Bad rawConfigOverride JSON: ${it.message}") }
                .getOrNull()
        }

    private companion object {
        const val TAG = "WheelRepository"
        const val BUNDLED_CONFIG = "tapp_widget_config.json"
        const val PLACEHOLDER_HOST_MARKER = "your-public-folder"
    }
}
