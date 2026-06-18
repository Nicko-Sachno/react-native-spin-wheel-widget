package com.spinwheelwidget.wheel.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ConfigResponse(
    val data: List<Data> = emptyList(),
    val meta: MetaData = MetaData()
)

// populate with default values from example
@Serializable
data class Rotation(
    // Spin duration. JSON key "duration" is in MILLISECONDS (2000 = 2s); suffix makes the unit explicit.
    @SerialName("duration") val durationMs: Long = 2000L,
    val minimumSpins: Int = 3,
    val maximumSpins: Int = 5,
    val spinEasing: String = "easeInOutCubic"
)

@Serializable
data class Assets(
    val bg: String = "bg.jpeg",
    val wheelFrame: String = "wheel-frame.png",
    val wheelSpin: String = "wheel-spin.png",
    val wheel: String = "wheel.png"
)

@Serializable
data class Wheel(
    val rotation: Rotation = Rotation(),
    val assets: Assets = Assets()
)

@Serializable
data class NetworkAssets(
    val host: String = ""
)

/**
 * Network/cache policy from the remote config.
 *
 * NOTE ON UNITS: the source JSON mixes units and never labels them, so we encode the unit in each
 * property name and map back to the original JSON key with @SerialName. The convention each field
 * follows comes from its domain's standard API:
 *  - Cache durations are in SECONDS (HTTP Cache-Control `max-age` heritage): 300 = 5 min, 3600 = 1 hr.
 *  - Timeouts are in MILLISECONDS (OkHttp/Java timeout APIs): 30,000 = 30 s.
 * Sanity check forces these: 30,000 as seconds would be ~8 hr (absurd); 300 as ms would be 0.3 s.
 *
 * refreshIntervalSec = soft TTL (cache goes stale → refresh in background, stale-while-revalidate).
 * cacheExpirationSec = hard TTL (cache no longer trustworthy → prefer a blocking fetch).
 * retryAttempts is a count; honoring it would require a retry Interceptor.
 */
@Serializable
data class Attributes(
    @SerialName("refreshInterval") val refreshIntervalSec: Long = 300,    // seconds
    @SerialName("networkTimeout") val networkTimeoutMs: Long = 30_000L,   // milliseconds
    @SerialName("retryAttempts") val retryAttempts: Int = 3,              // count
    @SerialName("cacheExpiration") val cacheExpirationSec: Long = 3_600L, // seconds
    @SerialName("debugMode") val debugMode: Boolean = false
)

@Serializable
data class MetaData(
    val version: Int = 1,
    val copyright: String = "Tapp"
)

@Serializable
data class Network(
    val attributes: Attributes = Attributes(),
    val assets: NetworkAssets = NetworkAssets()
)

@Serializable
data class Data(
    val id: String = "wheel_minimal",
    val name: String = "Minimal Wheel Widget Configuration",
    val type: String = "Widget",
    val network: Network = Network(),
    val wheel: Wheel = Wheel()
)