package com.spinwheelwidget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.spinwheelwidget.wheel.WheelRepository
import com.spinwheelwidget.wheel.WheelSpinWidget

/**
 * React Native bridge for the Spin Wheel home-screen widget.
 *
 * The widget itself is a native [WheelSpinWidget] (`AppWidgetProvider`) that lives in the launcher
 * process and runs even when the app is closed — so this module is a **headless data engine**, not
 * a rendered RN view. It writes config into the widget's SharedPreferences ([WheelRepository]),
 * asks the widget to reload, exposes the persisted state, and relays spin-completion events to JS
 * (while the app is alive) via [DeviceEventManagerModule.RCTDeviceEventEmitter].
 */
class SpinWheelWidgetModule(reactContext: ReactApplicationContext) :
  NativeSpinWheelWidgetSpec(reactContext) {

  private var listenerCount = 0
  private var spinResultReceiver: BroadcastReceiver? = null

  // One repository for the module's lifetime. It holds no per-call state (everything delegates to
  // SharedPreferences / disk), so sharing it is safe and avoids re-opening prefs on every bridge call.
  private val repo by lazy { WheelRepository(reactApplicationContext) }

  override fun getName(): String = NAME

  // ---- Config (writes to the widget's SharedPreferences) ----------------

  override fun configure(configUrl: String, assetsHost: String) {
    repo.configUrl = configUrl
    repo.assetsHostOverride = assetsHost
  }

  override fun setConfigJson(json: String) {
    // Empty string clears the override (falls back to network/bundled config).
    repo.rawConfigOverride = json.ifBlank { null }
  }

  // ---- Trigger a widget reload ------------------------------------------

  override fun refresh(promise: Promise) {
    try {
      val context = reactApplicationContext
      repo.forceNextRefresh() // explicit refresh bypasses the soft TTL
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, WheelSpinWidget::class.java))
      // Fire APPWIDGET_UPDATE → WheelSpinWidget.onUpdate reloads config, caches assets, re-renders.
      val intent = Intent(context, WheelSpinWidget::class.java).apply {
        action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
        putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
      }
      context.sendBroadcast(intent)
      promise.resolve(ids.isNotEmpty())
    } catch (e: Exception) {
      promise.reject("REFRESH_FAILED", e)
    }
  }

  // ---- Read persisted state ---------------------------------------------

  override fun getLastResult(): WritableMap {
    val result = repo.lastResult() // the landed sector index, derived from the persisted resting angle
    return Arguments.createMap().apply {
      putDouble("restingAngle", repo.restingAngle.toDouble())
      putDouble("lastFetchTime", repo.lastFetchTime.toDouble())
      putString("configUrl", repo.configUrl)
      putInt("segmentIndex", result.segmentIndex)
    }
  }

  // ---- Spin-result event relay ------------------------------------------

  override fun addListener(eventName: String) {
    if (listenerCount == 0) registerSpinReceiver()
    listenerCount++
  }

  override fun removeListeners(count: Double) {
    listenerCount = (listenerCount - count.toInt()).coerceAtLeast(0)
    if (listenerCount == 0) unregisterSpinReceiver()
  }

  override fun invalidate() {
    super.invalidate()
    unregisterSpinReceiver()
  }

  private fun registerSpinReceiver() {
    if (spinResultReceiver != null) return
    val receiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context, intent: Intent) {
        val params = Arguments.createMap().apply {
          putDouble("angle", intent.getFloatExtra(WheelSpinWidget.EXTRA_ANGLE, 0f).toDouble())
          putInt("widgetId", intent.getIntExtra(WheelSpinWidget.EXTRA_WIDGET_ID, -1))
          putInt("segmentIndex", intent.getIntExtra(WheelSpinWidget.EXTRA_SEGMENT_INDEX, -1))
        }
        if (reactApplicationContext.hasActiveReactInstance()) {
          reactApplicationContext
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit("onSpinResult", params)
        }
      }
    }
    spinResultReceiver = receiver
    ContextCompat.registerReceiver(
      reactApplicationContext,
      receiver,
      IntentFilter(WheelSpinWidget.ACTION_SPIN_RESULT),
      ContextCompat.RECEIVER_NOT_EXPORTED
    )
  }

  private fun unregisterSpinReceiver() {
    spinResultReceiver?.let {
      runCatching { reactApplicationContext.unregisterReceiver(it) }
      spinResultReceiver = null
    }
  }

  companion object {
    const val NAME = NativeSpinWheelWidgetSpec.NAME
  }
}
