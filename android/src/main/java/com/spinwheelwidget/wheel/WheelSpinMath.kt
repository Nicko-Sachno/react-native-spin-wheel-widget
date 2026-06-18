package com.spinwheelwidget.wheel

import com.spinwheelwidget.wheel.data.remote.Rotation
import kotlin.math.pow
import kotlin.random.Random

/**
 * A computed spin: where it starts, how many degrees it travels, and over how long.
 * Pure data with no Android types → trivially unit-testable.
 */
data class SpinPlan(val start: Float, val travel: Float, val durationMs: Long, val easing: String)

private const val MIN_DURATION_MS = 300L

/**
 * Decide a spin from the config and the current resting angle: a random whole number of full turns
 * (`minimumSpins..maximumSpins`) plus a random landing within the final turn, so every spin both
 * looks lively and stops somewhere new. [random] is injectable so tests can make it deterministic.
 */
fun planSpin(rotation: Rotation, start: Float, random: Random = Random): SpinPlan {
    val spins = random.nextInt(rotation.minimumSpins, rotation.maximumSpins + 1)
    val landing = random.nextInt(0, 360).toFloat()
    val travel = spins * 360f + landing
    return SpinPlan(start, travel, rotation.durationMs.coerceAtLeast(MIN_DURATION_MS), rotation.spinEasing)
}

/** The wheel angle at normalized progress [t] in [0,1], with the plan's easing applied. */
fun angleAt(plan: SpinPlan, t: Float): Float =
    plan.start + ease(plan.easing, t) * plan.travel

/**
 * Maps the config's `spinEasing` string to an easing curve (see easings.net). Case-insensitive;
 * an unknown/empty name falls back to easeInOutCubic (the sample config's default). This is what
 * makes `spinEasing` actually drive the animation instead of being a decorative field.
 */
fun ease(name: String, t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return when (name.lowercase()) {
        "linear" -> x
        "easeinquad" -> x * x
        "easeoutquad" -> 1f - (1f - x) * (1f - x)
        "easeinoutquad" -> if (x < 0.5f) 2f * x * x else 1f - (-2f * x + 2f).let { it * it } / 2f
        "easeincubic" -> x * x * x
        "easeoutcubic" -> (1f - x).let { 1f - it * it * it }
        "easeinoutcubic" -> easeInOutCubic(x)
        else -> easeInOutCubic(x)
    }
}

/**
 * Cubic ease-in-out — the standard "easeInOutCubic" curve (see easings.net). It's piecewise on t,
 * so the spin starts slow, accelerates, then decelerates smoothly to a stop:
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
fun easeInOutCubic(t: Float): Float =
    if (t < 0.5f) 4f * t * t * t
    else 1f - (-2f * t + 2f).toDouble().pow(3.0).toFloat() / 2f
