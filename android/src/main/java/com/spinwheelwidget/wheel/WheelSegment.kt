package com.spinwheelwidget.wheel

/**
 * The geometric outcome of a spin: which equal-sized sector sits under the fixed top pointer, plus
 * the resting angle that produced it. We deliberately report ONLY the sector index — not a color or
 * prize label — because the widget can't know the semantics of the artwork: `wheel.png` is opaque
 * pixels with no metadata, so any color/name we attached would be a guess that may not match what the
 * user sees. The index is a pure, verifiable fact given the [SEGMENT_COUNT] sector model below.
 */
data class SpinResult(
    val segmentIndex: Int,
    val angle: Float
)

// The wheel is modeled as 12 equal sectors (sweep = 360/12 = 30°), with sector 0 centered at the top
// (12 o'clock) under the pointer at rest. This is an ASSUMPTION about the artwork, not something read
// from it; if a future config describes the real sector layout, source the count + a pointer offset
// from there instead. Until then we report the index under this model and make no claim about labels.
const val SEGMENT_COUNT = 12

/**
 * Which sector sits under the fixed top pointer after the wheel rotated [angle]° clockwise.
 * The pointer is stationary and the wheel turns beneath it, so we map the NEGATIVE of the
 * rotation (360 - angle) into segment-sized sweeps.
 */
fun segmentAt(angle: Float, count: Int): Int {
    val sweep = 360f / count
    // CCW position of the pointer over the wheel, shifted by half a sweep so a segment "wins" while
    // its CENTER is under the pointer (the art's wedges are centered on 12 o'clock, not edge-aligned).
    val normalized = ((360f - (angle % 360f) + sweep / 2f) % 360f)
    return (normalized / sweep).toInt() % count
}
