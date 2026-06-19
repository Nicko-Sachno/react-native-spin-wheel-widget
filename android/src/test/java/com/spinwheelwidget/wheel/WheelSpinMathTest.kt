package com.spinwheelwidget.wheel

import com.spinwheelwidget.wheel.data.remote.Rotation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Unit tests for the pure spin/segment math (no Android types). These cover what a spin's correctness
 * hinges on: which segment wins under the pointer, the easing curve selected by `spinEasing`, and how
 * a spin is planned (turns + landing + duration). The Android-coupled layers (provider/repository/
 * bridge) are intentionally not unit-tested here — they'd need heavy mocking for little signal.
 */
class WheelSpinMathTest {

    // ---- segmentAt: 12 sectors, sweep = 30°, segment 0 centered at 12 o'clock (±15°) ----

    @Test
    fun `segment 0 is under the pointer at rest`() {
        assertEquals(0, segmentAt(0f, 12))
    }

    @Test
    fun `segment stays 0 within its centered half-sweep`() {
        assertEquals(0, segmentAt(14f, 12))   // just inside the +15° edge
        assertEquals(0, segmentAt(-14f, 12))  // symmetric on the other side
    }

    @Test
    fun `crossing the half-sweep boundary moves to the neighbor`() {
        assertEquals(11, segmentAt(16f, 12))  // just past +15° → CCW neighbor
        assertEquals(11, segmentAt(30f, 12))  // center of segment 11
    }

    @Test
    fun `angle is taken modulo 360`() {
        assertEquals(segmentAt(0f, 12), segmentAt(360f, 12))
        assertEquals(segmentAt(30f, 12), segmentAt(390f, 12))
    }

    @Test
    fun `result is always a valid index`() {
        for (a in -720..720 step 7) {
            val i = segmentAt(a.toFloat(), 12)
            assertTrue("index $i out of range for angle $a", i in 0 until 12)
        }
    }

    // ---- ease: spinEasing curve selection ----

    @Test
    fun `linear easing is the identity`() {
        assertEquals(0.3f, ease("linear", 0.3f), 1e-4f)
    }

    @Test
    fun `easeInOutCubic hits its anchors`() {
        assertEquals(0f, ease("easeInOutCubic", 0f), 1e-4f)
        assertEquals(0.5f, ease("easeInOutCubic", 0.5f), 1e-4f)
        assertEquals(1f, ease("easeInOutCubic", 1f), 1e-4f)
    }

    @Test
    fun `easeInCubic at the midpoint is one eighth`() {
        assertEquals(0.125f, ease("easeInCubic", 0.5f), 1e-4f)
    }

    @Test
    fun `unknown easing falls back to easeInOutCubic, and matching is case-insensitive`() {
        assertEquals(ease("easeInOutCubic", 0.4f), ease("totally-unknown", 0.4f), 1e-6f)
        assertEquals(ease("linear", 0.4f), ease("LINEAR", 0.4f), 1e-6f)
    }

    @Test
    fun `ease clamps t to the 0,1 range`() {
        assertEquals(0f, ease("linear", -1f), 1e-4f)
        assertEquals(1f, ease("linear", 2f), 1e-4f)
    }

    // ---- planSpin + angleAt ----

    private val rotation =
        Rotation(durationMs = 2000L, minimumSpins = 3, maximumSpins = 5, spinEasing = "linear")

    @Test
    fun `planSpin carries startAngle, duration, easing, and a travel in the configured range`() {
        val plan = planSpin(rotation, startAngle = 10f, random = Random(42))
        assertEquals(10f, plan.startAngle, 0f)
        assertEquals(2000L, plan.durationMs)
        assertEquals("linear", plan.easing)
        // 3..5 full turns + a [0,360) landing → [3*360, 6*360)
        assertTrue(plan.travelDegrees >= 3 * 360f && plan.travelDegrees < 6 * 360f)
    }

    @Test
    fun `planSpin enforces a minimum duration`() {
        val plan = planSpin(rotation.copy(durationMs = 50L), startAngle = 0f, random = Random(1))
        assertEquals(300L, plan.durationMs)
    }

    @Test
    fun `planSpin clamps an over-long duration to the broadcast-window ceiling`() {
        // A config that asked for 60s would run past the ~10s goAsync window → must be capped.
        val plan = planSpin(rotation.copy(durationMs = 60_000L), startAngle = 0f, random = Random(1))
        assertEquals(8_000L, plan.durationMs)
    }

    @Test
    fun `planSpin is deterministic for a fixed seed`() {
        assertEquals(planSpin(rotation, 0f, Random(7)), planSpin(rotation, 0f, Random(7)))
    }

    @Test
    fun `angleAt runs from startAngle to startAngle plus travel`() {
        val plan = SpinPlan(startAngle = 10f, travelDegrees = 720f, durationMs = 2000L, easing = "linear")
        assertEquals(10f, angleAt(plan, 0f), 1e-3f)
        assertEquals(370f, angleAt(plan, 0.5f), 1e-3f) // linear midpoint
        assertEquals(730f, angleAt(plan, 1f), 1e-3f)
    }
}
