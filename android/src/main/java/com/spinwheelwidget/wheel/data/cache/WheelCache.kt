package com.spinwheelwidget.wheel.data.cache

import android.content.Context
import com.spinwheelwidget.wheel.data.WheelSerializers
import com.spinwheelwidget.wheel.data.remote.ConfigResponse
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import java.io.File
import androidx.core.content.edit

class WheelCache(context: Context) {
    private val appContext =
        context.applicationContext // avoid leaking an Activity context if one is passed in
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    var lastConfigFetchTime: Long
        get() = prefs.getLong(KEY_LAST_FETCH, 0)
        set(value) = prefs.edit { putLong(KEY_LAST_FETCH, value) }

    // We store the andgle, at which the wheel has stopped spinning. This allows us to restore the wheel to the same position when the widget is redrawn.
    var restingAngle: Float
        get() = prefs.getFloat(KEY_ANGLE, 0f)
        set(value) = prefs.edit { putFloat(KEY_ANGLE, value % 360f) }

    // ---- Runtime config, set by the RN bridge via configure()/setConfigJson() -----------------

    // Remote config endpoint. Blank → the widget runs fully offline (bundled config + cache).
    var configUrl: String
        get() = prefs.getString(KEY_CONFIG_URL, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_CONFIG_URL, value) }

    // Optional override for the asset host. Blank → use the host declared inside the config.
    var assetsHostOverride: String
        get() = prefs.getString(KEY_ASSETS_HOST, "").orEmpty()
        set(value) = prefs.edit { putString(KEY_ASSETS_HOST, value) }

    // Optional full config JSON pushed straight from JS (offline override). Null → not set.
    var rawConfigOverride: String?
        get() = prefs.getString(KEY_RAW_CONFIG, null)
        set(value) = prefs.edit {
            if (value == null) remove(KEY_RAW_CONFIG) else putString(KEY_RAW_CONFIG, value)
        }

    // Concurrency: no lock/Mutex around these disk + prefs writes — deliberately. Widget wakes are
    // serialized broadcasts that rarely overlap, the worst case of a rare race is a redundant
    // re-fetch (self-healing, never corrupting), and a Mutex would be overkill for this assignment.
    @OptIn(ExperimentalSerializationApi::class)
    fun saveConfig(response: ConfigResponse) = runCatching {
        configFile().writeBytes(WheelSerializers.cbor.encodeToByteArray(response))
    }
        .onFailure { it.printStackTrace() }

    @OptIn(ExperimentalSerializationApi::class)
    fun readConfig(): ConfigResponse? = runCatching {
        val file = configFile()
        if (file.exists())
            WheelSerializers.cbor.decodeFromByteArray<ConfigResponse>(file.readBytes())
        else
            null
    }
        .onFailure { it.printStackTrace() }
        .getOrNull()

    fun assetFile(relativePath: String): File =
        File(assetDir(), relativePath.substringAfterLast('/'))

    fun hasAsset(relativePath: String): Boolean =
        assetFile(relativePath).exists()

    fun saveAsset(relativePath: String, bytes: ByteArray) {
        assetDir().mkdirs() // if path doesn't exist, create it
        assetFile(relativePath).writeBytes(bytes)
    }

    fun pruneAssetsExcept(keep:Set<String>) = runCatching {
        assetDir().listFiles()?.forEach {
            if (it.name !in keep) it.delete()
        }
    }


    // helpers
    private fun configFile(): File = File(appContext.filesDir, CONFIG_FILE)
    private fun assetDir(): File = File(appContext.cacheDir, ASSET_DIR)

    private companion object {
        const val PREFS = "wheel_widget_prefs"
        const val KEY_LAST_FETCH = "last_config_fetch_time"
        const val KEY_ANGLE = "resting_angle"
        const val KEY_CONFIG_URL = "config_url"
        const val KEY_ASSETS_HOST = "assets_host_override"
        const val KEY_RAW_CONFIG = "raw_config_override"
        const val CONFIG_FILE = "wheel_config.cbor"
        const val ASSET_DIR = "wheel_assets"
    }
}