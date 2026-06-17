package com.spinwheelwidget

import com.facebook.react.bridge.ReactApplicationContext

class SpinWheelWidgetModule(reactContext: ReactApplicationContext) :
  NativeSpinWheelWidgetSpec(reactContext) {

  override fun multiply(a: Double, b: Double): Double {
    return a * b
  }

  companion object {
    const val NAME = NativeSpinWheelWidgetSpec.NAME
  }
}
