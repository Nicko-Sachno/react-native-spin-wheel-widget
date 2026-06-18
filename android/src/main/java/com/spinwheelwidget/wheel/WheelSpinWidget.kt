package com.spinwheelwidget.wheel

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.util.Log
import android.widget.RemoteViews
import androidx.annotation.DrawableRes
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.graphics.withRotation
import androidx.core.net.toUri
import com.spinwheelwidget.R
import com.spinwheelwidget.wheel.SpinPlan
import com.spinwheelwidget.wheel.WheelRepository
import com.spinwheelwidget.wheel.angleAt
import com.spinwheelwidget.wheel.planSpin
import com.spinwheelwidget.wheel.data.remote.Data
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Spin Wheel home-screen widget — classic [AppWidgetProvider] + [RemoteViews]. A widget renders in
 * the launcher process, so we can't use Compose/Glance/property animations; the spin is produced by
 * rotating the wheel bitmap frame-by-frame in OUR process and pushing each frame to the launcher
 * via [RemoteViews.setImageViewBitmap] + [AppWidgetManager.partiallyUpdateAppWidget].
 */
class WheelSpinWidget : AppWidgetProvider() {

    // ---- System lifecycle callbacks -------------------------------------

    // System refresh / widget added. Paint a synchronous baseline, then hydrate in the background.
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        // (1) Instant baseline (layout android:src fallbacks + click) so the widget is never blank.
        appWidgetIds.forEach { id ->
            appWidgetManager.updateAppWidget(id, baseViews(context, id))
        }
        // (2) Background hydrate: the work can outlive this callback, so it runs on our scope.
        scope.launch {
            val repo = repository(context)
            val config = repo.refresh()      // resolve config (cache or network, per the TTL policy)
            repo.cacheAssets(config)         // download missing images + prune orphans (reconcile)
            // Re-render every placed instance with the real downloaded art at the persisted angle.
            appWidgetIds.forEach { id -> renderStatic(context, appWidgetManager, id, repo, config) }
        }
    }

    // Tap on the spin button arrives here as our ACTION_SPIN broadcast (see [spinIntent]).
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_SPIN) return
        val id = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return

        // goAsync() tells the system "this broadcast isn't done yet" — it keeps the process alive and
        // holds the broadcast window open (~10s) while the spin coroutine runs. finish() releases it.
        val pending = goAsync()
        scope.launch {
            try {
                animateSpin(context, AppWidgetManager.getInstance(context), id)
            } finally {
                pending.finish()
            }
        }
    }

    // First widget instance placed (0 → 1 instances). Start the wheel at green-at-top (angle 0), so a
    // fresh placement always begins from a known position regardless of any previously persisted angle.
    override fun onEnabled(context: Context) {
        repository(context).restingAngle = 0f
    }

    // ---- Rendering -------------------------------------------------------

    /** Static layout baseline + the tap intent. Layers fall back to the layout's android:src. */
    private fun baseViews(context: Context, id: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.wheel_spin_widget).apply {
            setOnClickPendingIntent(R.id.wheel_spin, spinIntent(context, id))
        }

    /** Render all 4 layers from the (downloaded or fallback) assets, wheel at its resting angle. */
    private fun renderStatic(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        repo: WheelRepository,
        config: Data,
    ) {
        val layers = decodeLayers(context, repo, config)
        pushLayers(context, manager, id, layers, rotate(layers.wheel, repo.restingAngle))
    }

    /** Decode + size-cap the four layer bitmaps referenced by [config]. */
    private fun decodeLayers(context: Context, repo: WheelRepository, config: Data): Layers {
        val a = config.wheel.assets
        // bg/frame/spin: capped() preserves aspect ratio (they're not rotated). The wheel is forced to
        // an exact TARGET_PX square via scale() because we rotate it around its center every frame — a
        // square keeps the rotation centered and the double-buffer math (size = width = height) simple.
        return Layers(
            bg = resolveBitmap(context, repo, a.bg, R.drawable.bg).capped(TARGET_PX),
            frame = resolveBitmap(context, repo, a.wheelFrame, R.drawable.wheel_frame).capped(TARGET_PX),
            spin = resolveBitmap(context, repo, a.wheelSpin, R.drawable.wheel_spin).capped(TARGET_PX),
            wheel = resolveBitmap(context, repo, a.wheel, R.drawable.wheel).scale(TARGET_PX, TARGET_PX),
        )
    }

    /**
     * Push the layers as SEPARATE Binder transactions (one bitmap each). setImageViewBitmap ships the
     * bitmap over Binder, whose buffer is ~1MB *per transaction*; four ~410KB bitmaps in one
     * updateAppWidget can overflow it (TransactionTooLargeException). Splitting keeps each well under.
     * The first call is a full update (baseline + click); the rest are partial (merge onto it).
     * [wheelFrame] is the wheel bitmap already rotated to the desired angle.
     */
    private fun pushLayers(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        layers: Layers,
        wheelFrame: Bitmap,
    ) {
        manager.updateAppWidget(id, baseViews(context, id).apply { setImageViewBitmap(R.id.wheel_bg, layers.bg) })
        manager.partiallyUpdateAppWidget(id, partialViews(context) { setImageViewBitmap(R.id.wheel_frame, layers.frame) })
        manager.partiallyUpdateAppWidget(id, partialViews(context) { setImageViewBitmap(R.id.wheel_spin, layers.spin) })
        manager.partiallyUpdateAppWidget(id, partialViews(context) { setImageViewBitmap(R.id.wheel_image, wheelFrame) })
    }

    /** Prefer the downloaded asset; fall back to the bundled drawable if not on disk yet. */
    private fun resolveBitmap(
        context: Context,
        repo: WheelRepository,
        relative: String,
        @DrawableRes fallbackRes: Int,
    ): Bitmap {
        val file = repo.cachedAssetFile(relative)
        return if (file != null) BitmapFactory.decodeFile(file.absolutePath)
        else BitmapFactory.decodeResource(context.resources, fallbackRes)
    }

    // ---- Spin animation --------------------------------------------------

    /** Orchestrate a spin: decode layers, plan it, render the baseline, run the frames, persist + log. */
    private suspend fun animateSpin(context: Context, manager: AppWidgetManager, id: Int) {
        val repo = repository(context)
        val config = repo.snapshot().config // cached config — instant, no network on a tap
        val layers = decodeLayers(context, repo, config)
        val plan = planSpin(config.wheel.rotation, repo.restingAngle)

        // Re-establish the full baseline (all 4 layers, wheel at the start angle) so the spin is
        // correct even if the launcher dropped our last render. The loop then repaints only the wheel.
        pushLayers(context, manager, id, layers, rotate(layers.wheel, plan.start))
        runSpinFrames(context, manager, id, layers.wheel, plan)

        repo.restingAngle = plan.start + plan.travel // setter normalizes % 360
        broadcastResult(context, id, repo)
    }

    /**
     * The frame loop. Each frame: rotate the wheel into the idle double-buffer and push only that
     * layer via partiallyUpdateAppWidget. It is:
     *  - wall-clock driven (t = elapsed / duration): a late/dropped frame jumps the angle forward
     *    instead of dragging the whole spin, so perceived speed stays constant;
     *  - work-compensated (delay = budget − work): cadence targets the true ~60fps budget;
     *  - double-buffered (two reused bitmaps): zero per-frame allocation → no GC stutter. The buffer
     *    is fully parceled by the time partiallyUpdateAppWidget returns, so swapping it is safe.
     */
    private suspend fun runSpinFrames(
        context: Context,
        manager: AppWidgetManager,
        id: Int,
        wheel: Bitmap,
        plan: SpinPlan,
    ) {
        // The wheel was scaled to a square (TARGET_PX × TARGET_PX), so width == height == size.
        val size = wheel.width

        // DOUBLE BUFFER: two reusable bitmaps (+ a Canvas each). We render frame N into the *idle*
        // buffer, push it, then swap — so we never allocate a new bitmap per frame. Reusing them is
        // what avoids GC stutter mid-spin (the naive approach throws away a ~410KB bitmap every frame).
        val buffers = Array(2) { createBitmap(size, size) }
        val canvases = Array(2) { Canvas(buffers[it]) }
        // Draw target = the whole bitmap; renderInto() paints the rotated wheel into this rect.
        val dst = Rect(0, 0, size, size)

        val stats = if (MEASURE) FrameStats(plan.durationMs, FRAME_MS) else null
        // Anchor the spin to the wall clock (nanoseconds). Each frame's angle is derived from REAL
        // elapsed time, not a frame count — so a late/dropped frame just jumps the angle forward and
        // the spin still finishes on time at a constant perceived speed (instead of slowing down).
        val startNs = System.nanoTime()
        var slot = 0          // index (0/1) of the idle buffer to draw into this frame
        var prevPushNs = 0L   // timestamp of the previous push — only used for the stats interval
        while (true) {
            val frameStartNs = System.nanoTime()
            // Progress t ∈ [0,1]:  (elapsed ns ÷ 1_000_000) = elapsed ms  (1e6 ns per ms),  ÷ duration ms.
            // coerceAtMost(1f) clamps the last frame so the wheel lands exactly on target, never past it.
            val t = ((frameStartNs - startNs) / 1_000_000f / plan.durationMs).coerceAtMost(1f)

            // Rotate the wheel to its eased angle for this instant into the idle buffer…
            renderInto(canvases[slot], dst, wheel, angleAt(plan, t))
            // …then push ONLY the wheel layer. A partial update means the launcher keeps the
            // bg/frame/spin it already has and doesn't re-inflate the layout ~60×/sec — that's what
            // keeps the per-frame cross-process (Binder) cost cheap enough to look smooth.
            manager.partiallyUpdateAppWidget(
                id, partialViews(context) { setImageViewBitmap(R.id.wheel_image, buffers[slot]) }
            )
            // Flip 0↔1 (xor toggles the low bit): next frame uses the other buffer while this one is
            // still being parceled to the launcher.
            slot = slot xor 1

            if (stats != null) {
                val now = System.nanoTime()
                stats.record(
                    workMs = (now - frameStartNs) / 1_000_000f,                       // ns → ms
                    intervalMs = if (prevPushNs == 0L) -1f else (now - prevPushNs) / 1_000_000f, // -1 = first frame
                )
                prevPushNs = now
            }

            if (t >= 1f) break // we just rendered the final (landing) frame → stop

            // Pace to ~60fps WITHOUT drift: sleep the *remainder* of the frame budget after subtracting
            // the time this frame already spent rendering+pushing. coerceAtLeast(0) handles the case
            // where the work already overran the budget (then we don't sleep, just go to the next frame).
            val workMs = (System.nanoTime() - frameStartNs) / 1_000_000f
            delay((FRAME_MS - workMs).toLong().coerceAtLeast(0L).milliseconds)
        }
        stats?.log(startNs) // MEASURE on → emit the one-line smoothness summary for this spin
    }

    /**
     * Broadcast the landed segment (the "prize") so the RN bridge can relay it to JS, and log it.
     * The intent is package-scoped; the bridge registers a NOT_EXPORTED receiver for ACTION_SPIN_RESULT.
     */
    private fun broadcastResult(context: Context, id: Int, repo: WheelRepository) {
        val result = repo.lastResult()
        Log.i(
            TAG,
            "Spin landed on segment ${result.segmentIndex} " +
                "${result.segment.name} (${result.segment.color}) @ ${result.angle}°"
        )
        val intent = Intent(ACTION_SPIN_RESULT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ANGLE, result.angle)
            putExtra(EXTRA_WIDGET_ID, id)
            putExtra(EXTRA_SEGMENT_INDEX, result.segmentIndex)
            putExtra(EXTRA_COLOR, result.segment.color)
            putExtra(EXTRA_NAME, result.segment.name)
        }
        context.sendBroadcast(intent)
    }

    // ---- Helpers ---------------------------------------------------------

    /** Single shared repository, built lazily from the application context (safe to hold statically)
     *  and reused across all broadcast callbacks instead of re-creating it on each one. */
    private fun repository(context: Context): WheelRepository =
        repoInstance ?: WheelRepository(context).also { repoInstance = it }

    private fun partialViews(context: Context, block: RemoteViews.() -> Unit): RemoteViews =
        RemoteViews(context.packageName, R.layout.wheel_spin_widget).apply(block)

    /** Rotate [src] by [degrees] around its center into a fresh bitmap (used for single static frames). */
    private fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val out = createBitmap(src.width, src.height)
        renderInto(Canvas(out), Rect(0, 0, src.width, src.height), src, degrees)
        return out
    }

    /** Rotate [src] by [degrees] into the (reused) [canvas]/buffer, clearing it first. */
    private fun renderInto(canvas: Canvas, dst: Rect, src: Bitmap, degrees: Float) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.withRotation(degrees, dst.width() / 2f, dst.height() / 2f) {
            drawBitmap(src, null, dst, framePaint)
        }
    }

    /** Downscale so the longest side is at most [maxPx], preserving aspect (keeps each bitmap small). */
    private fun Bitmap.capped(maxPx: Int): Bitmap {
        val longest = maxOf(width, height)
        if (longest <= maxPx) return this
        val s = maxPx.toFloat() / longest
        return scale((width * s).toInt(), (height * s).toInt())
    }

    /**
     * The PendingIntent fired when the user taps the spin button. The widget's views live in the
     * launcher process, so the only way to "hear" a tap is to hand the launcher a PendingIntent that
     * it fires on our behalf — a broadcast back to THIS provider's onReceive.
     */
    private fun spinIntent(context: Context, id: Int): PendingIntent {
        val intent = Intent(context, WheelSpinWidget::class.java).apply {
            action = ACTION_SPIN                              // routed to onReceive's ACTION_SPIN branch
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id) // which widget instance was tapped
            data = "spinwheel://wheel/$id".toUri()            // unique per id so PendingIntents don't collide
        }
        return PendingIntent.getBroadcast(
            context, id, intent,
            // UPDATE_CURRENT: refresh extras if it already exists. IMMUTABLE: required on API 31+.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** The four decoded, size-capped layer bitmaps for one render. */
    private class Layers(val bg: Bitmap, val frame: Bitmap, val spin: Bitmap, val wheel: Bitmap)

    /**
     * Collects per-frame timing for one spin and logs a one-line summary (tag "WheelSpin"). The
     * smoothness signals are the inter-frame interval's jitter (std-dev) and the dropped-frame count
     * (gaps > 1.5× the target budget) — both should be low. Read it with: `adb logcat -s WheelSpin`.
     */
    private class FrameStats(private val durationMs: Long, private val targetMs: Long) {
        private val intervals = ArrayList<Float>(64)
        private var frames = 0
        private var workSumMs = 0f
        private var workMaxMs = 0f

        fun record(workMs: Float, intervalMs: Float) {
            frames++
            workSumMs += workMs
            if (workMs > workMaxMs) workMaxMs = workMs
            if (intervalMs >= 0f) intervals.add(intervalMs) // first frame has no previous → skip
        }

        fun log(startNs: Long) {
            val totalMs = (System.nanoTime() - startNs) / 1_000_000f
            val mean = if (intervals.isEmpty()) 0f else intervals.average().toFloat()
            val variance = intervals.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } /
                intervals.size.coerceAtLeast(1)
            val jitter = sqrt(variance)
            val maxInterval = intervals.maxOrNull() ?: 0f
            // A frame is "dropped" when the gap stretches past 1.5× the target budget.
            val dropped = intervals.count { it > targetMs * 1.5f }
            val achievedFps = if (totalMs <= 0f) 0f else frames * 1000f / totalMs
            Log.i(
                "WheelSpin",
                "frames=$frames fps=${"%.1f".format(achievedFps)} (target=${"%.0f".format(1000f / targetMs)}) " +
                    "interval avg=${"%.1f".format(mean)}ms jitter=±${"%.1f".format(jitter)}ms " +
                    "max=${"%.1f".format(maxInterval)}ms dropped=$dropped " +
                    "work avg=${"%.1f".format(if (frames == 0) 0f else workSumMs / frames)}ms " +
                    "max=${"%.1f".format(workMaxMs)}ms total=${"%.0f".format(totalMs)}ms (budget=${durationMs}ms)"
            )
        }
    }

    // Non-private: the RN bridge (SpinWheelWidgetModule) reads the result action + extra keys.
    companion object {
        const val ACTION_SPIN_RESULT = "com.spinwheelwidget.wheel.ACTION_SPIN_RESULT"
        const val EXTRA_ANGLE = "angle"
        const val EXTRA_WIDGET_ID = "widgetId"
        const val EXTRA_SEGMENT_INDEX = "segmentIndex"
        const val EXTRA_COLOR = "color"
        const val EXTRA_NAME = "name"

        // ---- Provider internals (not part of the bridge contract) ----
        private const val TAG = "WheelSpin"
        private const val ACTION_SPIN = "com.spinwheelwidget.wheel.ACTION_SPIN_V2"

        /** Square px for the wheel bitmap. 320²·4 ≈ 410KB — safely under the ~1MB Binder limit. */
        private const val TARGET_PX = 320

        /** Per-frame budget in ms: 16 ≈ 1000ms / 60fps. The spin loop paces itself to this. */
        private const val FRAME_MS = 16L

        /** Flip to false to silence the per-spin cadence log (tag "WheelSpin"). */
        private const val MEASURE = true

        // Blocking network + disk I/O → IO dispatcher (elastic pool), not Default (CPU pool).
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // One shared repository for all callbacks (built from the application context → no leak).
        @Volatile
        private var repoInstance: WheelRepository? = null
    }
}
