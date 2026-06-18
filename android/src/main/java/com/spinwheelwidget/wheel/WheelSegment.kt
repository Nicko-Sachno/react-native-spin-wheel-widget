package com.spinwheelwidget.wheel

data class WheelSegment(
    val index: Int,
    val color: String, // hex
    val name: String    // human-readable color name (later: label, prizeId, item, etc...)
)

data class SpinResult(
    val segmentIndex: Int,
    val segment: WheelSegment,
    val angle: Float
)

// The wheel art has 12 equal sectors: one special GREEN sector (index 0, at 12 o'clock) and the
// other 11 alternating RED / PURPLE. sweep = 360/12 = 30°. At rest (angle 0°) the top pointer sits
// over index 0 (Green) — matching the art's green wedge at 12 o'clock.
// TODO: source segments + a pointer offset from config to match the art pixel-perfectly.
private const val SEGMENT_COUNT = 12

val DEFAULT_SEGMENTS: List<WheelSegment> = List(SEGMENT_COUNT) { i ->
    when {
        i == 0 -> WheelSegment(i, "#7CB342", "Green")
        i % 2 == 1 -> WheelSegment(i, "#8E24AA", "Purple")
        else -> WheelSegment(i, "#26C6DA", "Mint")
    }
}

/**
 * Which segment sits under the fixed top pointer after the wheel rotated [angle]° clockwise.
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
