package com.spinwheelwidget.wheel

import com.spinwheelwidget.wheel.data.remote.Rotation
import kotlin.math.pow
import kotlin.random.Random

/**
 * A computed spin: the angle it starts from, how many degrees it travels, and over how long.
 * Pure data with no Android types → trivially unit-testable.
 */
data class SpinPlan(
    val startAngle: Float,
    val travelDegrees: Float,
    val durationMs: Long,
    val easing: String,
)

private const val MIN_DURATION_MS = 300L

// The spin runs inside an AppWidgetProvider broadcast's goAsync() window, which must finish() before
// the system's ANR timer (~10s) or the process is torn down mid-spin. We clamp the config's duration
// to a hard ceiling that leaves margin for asset decode + the frame loop, so a hostile/typo config
// (e.g. duration: 60000) can never run past the window. Genuinely long spins would need a foreground
// service / WorkManager instead — out of scope here.
private const val MAX_DURATION_MS = 8_000L

/**
 * Decide a spin from the config and the current [startAngle] (the wheel's resting angle): a random
 * whole number of full turns (`minimumSpins..maximumSpins`) plus a random landing within the final
 * turn, so every spin both looks lively and stops somewhere new. [random] is injectable so tests can
 * make it deterministic. Duration is clamped to [[MIN_DURATION_MS], [MAX_DURATION_MS]] (see above).
 */
fun planSpin(rotation: Rotation, startAngle: Float, random: Random = Random): SpinPlan {
    val fullTurns = random.nextInt(rotation.minimumSpins, rotation.maximumSpins + 1)
    val landingAngle = random.nextInt(0, 360).toFloat()
    val travelDegrees = fullTurns * 360f + landingAngle
    return SpinPlan(
        startAngle = startAngle,
        travelDegrees = travelDegrees,
        durationMs = rotation.durationMs.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS),
        easing = rotation.spinEasing,
    )
}

/** The wheel angle at normalized [progress] in [0,1], with the plan's easing applied. */
fun angleAt(plan: SpinPlan, progress: Float): Float =
    plan.startAngle + ease(plan.easing, progress) * plan.travelDegrees

/**
 * Maps the config's `spinEasing` string to an easing curve (see easings.net). Case-insensitive;
 * an unknown/empty name falls back to easeInOutCubic (the sample config's default). This is what
 * makes `spinEasing` actually drive the animation instead of being a decorative field.
 */
fun ease(name: String, progress: Float): Float {
    val clamped = progress.coerceIn(0f, 1f)
    return when (name.lowercase()) {
        "linear" -> clamped
        "easeinquad" -> clamped * clamped
        "easeoutquad" -> 1f - (1f - clamped) * (1f - clamped)
        "easeinoutquad" -> if (clamped < 0.5f) 2f * clamped * clamped else 1f - (-2f * clamped + 2f).let { it * it } / 2f
        "easeincubic" -> clamped * clamped * clamped
        "easeoutcubic" -> (1f - clamped).let { 1f - it * it * it }
        "easeinoutcubic" -> easeInOutCubic(clamped)
        else -> easeInOutCubic(clamped)
    }
}

/**
 * Cubic ease-in-out — the standard "easeInOutCubic" curve (see easings.net). [progress] (t) is
 * piecewise, so the spin starts slow, accelerates, then decelerates smoothly to a stop:
 *  - first half  (t < 0.5): ease IN  → `4·t³`            (a cubic that's flat at t=0)
 *  - second half (t ≥ 0.5): ease OUT → the in-curve mirrored: `1 − (−2t+2)³ / 2`
 *
 * Where the constants come from (so they're not magic):
 *  - We want f(0)=0, f(0.5)=0.5, f(1)=1 with matching slope at the midpoint (no visible kink).
 *  - On [0,0.5], plain t³ only reaches 0.125 at 0.5; multiplying by **4** (= 2³/2) rescales it to
 *    hit exactly 0.5 at the midpoint.
 *  - On [0.5,1], `(−2t+2)` maps t∈[0.5,1] → [1,0]; cubing it and halving, then subtracting from 1,
 *    reflects the in-curve into a symmetric ease-out. (`.toDouble().pow(3.0)` just does the cube.)
 */
fun easeInOutCubic(progress: Float): Float =
    if (progress < 0.5f) 4f * progress * progress * progress
    else 1f - (-2f * progress + 2f).toDouble().pow(3.0).toFloat() / 2f
