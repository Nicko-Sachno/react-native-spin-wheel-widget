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
import android.os.SystemClock
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
        appWidgetIds.forEach { widgetId ->
            appWidgetManager.updateAppWidget(widgetId, baseViews(context, widgetId))
        }
        // (2) Background hydrate: the work can outlive this callback, so it runs on our scope.
        scope.launch {
            val repo = repository(context)
            val config = repo.refresh()      // resolve config (cache or network, per the TTL policy)
            repo.cacheAssets(config)         // download missing images + prune orphans (reconcile)
            // Re-render every placed instance with the real downloaded art at the persisted angle.
            appWidgetIds.forEach { widgetId -> renderStatic(context, appWidgetManager, widgetId, repo, config) }
        }
    }

    // Tap on the spin button arrives here as our ACTION_SPIN broadcast (see [spinIntent]).
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_SPIN) return
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID
        )
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) return

        // Gate: you can only spin when the wheel is at REST. This needs two layers, because a single
        // "is spinning" flag is not enough here:
        //   goAsync() (below) holds the broadcast open for the whole spin, and the OS delivers
        //   ACTION_SPIN broadcasts to a receiver SERIALLY. So taps made *during* a spin are not
        //   delivered now — the system QUEUES them and replays them back-to-back the moment finish()
        //   runs. By then the in-memory flag is already cleared, so the flag alone lets that backlog
        //   through → the wheel spins once per queued tap (the "counts taps, runs them one by one" bug).
        // Fix: (a) drop anything while a spin is in flight, AND (b) drop anything delivered within
        // TAP_COOLDOWN_MS of the last spin ending — which is precisely that queued backlog. cooldownUntil
        // SLIDES on every rejected tap, so an arbitrarily long backlog (or continued mashing) drains
        // fully; a new spin starts only after the user pauses past the cooldown with the wheel at rest.
        val nowMs = SystemClock.elapsedRealtime()
        if (widgetId in spinning || nowMs < (cooldownUntil[widgetId] ?: 0L)) {
            cooldownUntil[widgetId] = nowMs + TAP_COOLDOWN_MS
            return
        }
        if (!spinning.add(widgetId)) return

        // goAsync() tells the system "this broadcast isn't done yet" — it keeps the process alive and
        // holds the broadcast window open (~10s) while the spin coroutine runs. finish() releases it.
        val pendingResult = goAsync()
        scope.launch {
            try {
                animateSpin(context, AppWidgetManager.getInstance(context), widgetId)
            } finally {
                spinning.remove(widgetId)
                // Arm the cooldown BEFORE finish(): finish() releases the broadcast queue, so the
                // queued backlog starts arriving immediately and must find the cooldown already set.
                cooldownUntil[widgetId] = SystemClock.elapsedRealtime() + TAP_COOLDOWN_MS
                pendingResult.finish()
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
    private fun baseViews(context: Context, widgetId: Int): RemoteViews =
        RemoteViews(context.packageName, R.layout.wheel_spin_widget).apply {
            setOnClickPendingIntent(R.id.wheel_spin, spinIntent(context, widgetId))
        }

    /** Render all 4 layers from the (downloaded or fallback) assets, wheel at its resting angle. */
    private fun renderStatic(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        repo: WheelRepository,
        config: Data,
    ) {
        val layers = decodeLayers(context, repo, config)
        pushLayers(context, manager, widgetId, layers, rotate(layers.wheel, repo.restingAngle))
    }

    /** Decode + size-cap the four layer bitmaps referenced by [config]. */
    private fun decodeLayers(context: Context, repo: WheelRepository, config: Data): Layers {
        val assets = config.wheel.assets
        // bg/frame/spin: capped() preserves aspect ratio (they're not rotated). The wheel is forced to
        // an exact TARGET_PX square via scale() because we rotate it around its center every frame — a
        // square keeps the rotation centered and the buffer math (sizePx = width = height) simple.
        return Layers(
            bg = resolveBitmap(context, repo, assets.bg, R.drawable.bg).capped(TARGET_PX),
            frame = resolveBitmap(context, repo, assets.wheelFrame, R.drawable.wheel_frame).capped(TARGET_PX),
            spin = resolveBitmap(context, repo, assets.wheelSpin, R.drawable.wheel_spin).capped(TARGET_PX),
            wheel = resolveBitmap(context, repo, assets.wheel, R.drawable.wheel).scale(TARGET_PX, TARGET_PX),
        )
    }

    /**
     * Push the layers as SEPARATE Binder transactions (one bitmap each). setImageViewBitmap ships the
     * bitmap over Binder, whose buffer is ~1MB *per transaction*; four ~410KB bitmaps in one
     * updateAppWidget can overflow it (TransactionTooLargeException). Splitting keeps each well under.
     * The first call is a full update (baseline + click); the rest are partial (merge onto it).
     * [rotatedWheel] is the wheel bitmap already rotated to the desired angle.
     */
    private fun pushLayers(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        layers: Layers,
        rotatedWheel: Bitmap,
    ) {
        manager.updateAppWidget(widgetId, baseViews(context, widgetId).apply { setImageViewBitmap(R.id.wheel_bg, layers.bg) })
        manager.partiallyUpdateAppWidget(widgetId, partialViews(context) { setImageViewBitmap(R.id.wheel_frame, layers.frame) })
        manager.partiallyUpdateAppWidget(widgetId, partialViews(context) { setImageViewBitmap(R.id.wheel_spin, layers.spin) })
        manager.partiallyUpdateAppWidget(widgetId, partialViews(context) { setImageViewBitmap(R.id.wheel_image, rotatedWheel) })
    }

    /** Prefer the downloaded asset; fall back to the bundled drawable if not on disk yet. */
    private fun resolveBitmap(
        context: Context,
        repo: WheelRepository,
        relativePath: String,
        @DrawableRes fallbackRes: Int,
    ): Bitmap {
        val cachedFile = repo.cachedAssetFile(relativePath)
        return if (cachedFile != null) BitmapFactory.decodeFile(cachedFile.absolutePath)
        else BitmapFactory.decodeResource(context.resources, fallbackRes)
    }

    // ---- Spin animation --------------------------------------------------

    /** Orchestrate a spin: decode layers, plan it, render the baseline, run the frames, persist + log. */
    private suspend fun animateSpin(context: Context, manager: AppWidgetManager, widgetId: Int) {
        val repo = repository(context)
        val config = repo.snapshot().config // cached config — instant, no network on a tap
        val layers = decodeLayers(context, repo, config)
        val plan = planSpin(config.wheel.rotation, repo.restingAngle)

        // Persist the landing angle UP FRONT (setter normalizes % 360). The spin runs inside the
        // ~10s broadcast window; if the process is torn down mid-spin, the next render still restores
        // the wheel to the intended result (it just snaps there without the animation) instead of
        // leaving a stale/half-spun angle. On the normal path this is just the final state, written early.
        repo.restingAngle = plan.startAngle + plan.travelDegrees

        // Re-establish the full baseline (all 4 layers, wheel at the start angle) so the spin is
        // correct even if the launcher dropped our last render. The loop then repaints only the wheel.
        pushLayers(context, manager, widgetId, layers, rotate(layers.wheel, plan.startAngle))
        runSpinFrames(context, manager, widgetId, layers.wheel, plan)

        broadcastResult(context, widgetId, repo)
    }

    /**
     * The spin frame loop. Each frame: rotate the wheel to its eased angle into a single reusable
     * bitmap and push only that layer via partiallyUpdateAppWidget. It is:
     *  - wall-clock driven (progress = time elapsed since the spin started / total duration): a
     *    late/dropped frame jumps the angle forward instead of dragging the whole spin, so the spin
     *    finishes on schedule;
     *  - work-compensated (sleep = budget − work done this frame): cadence targets the true ~60fps budget;
     *  - single-buffered: one bitmap reused every frame (render → push → render again into the SAME
     *    buffer), so there's zero per-frame allocation.
     *
     * Reusing one buffer is safe because partiallyUpdateAppWidget copies the bitmap's pixels into the
     * parcel synchronously, before it returns — AOSP's `Bitmap.writeToParcel` ("write the bitmap and
     * its pixels to the parcel") plus the synchronous Binder transact — so by the time we overwrite the
     * buffer for the next frame, the launcher already has its own copy. (Verified visually: spinning
     * from a single reused buffer shows no tearing and no frozen frames.)
     */
    private suspend fun runSpinFrames(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        wheel: Bitmap,
        plan: SpinPlan,
    ) {
        // The wheel was scaled to a square (TARGET_PX × TARGET_PX), so width == height == sizePx.
        val sizePx = wheel.width

        // One reusable bitmap + canvas, rewritten every frame. targetRect = the whole bitmap;
        // renderInto() paints the rotated wheel into this rect.
        val frameBuffer = createBitmap(sizePx, sizePx)
        val frameCanvas = Canvas(frameBuffer)
        val targetRect = Rect(0, 0, sizePx, sizePx)

        val frameStats = if (MEASURE) FrameStats(plan.durationMs, FRAME_MS) else null
        // Anchor the spin to the wall clock (nanoseconds). Each frame's angle is derived from REAL
        // time elapsed since this point, not a frame count — so a late/dropped frame just jumps the
        // angle forward and the spin still finishes on time (instead of slowing down).
        val spinStartNs = System.nanoTime()
        var previousPushNs = 0L   // timestamp of the previous push — only used for the stats interval
        while (true) {
            val frameStartNs = System.nanoTime()
            // progress ∈ [0,1]:  elapsed-since-start in ns ÷ 1_000_000 = elapsed ms (1e6 ns per ms),
            // ÷ total duration ms. coerceAtMost(1f) clamps the last frame so the wheel lands exactly
            // on target, never past it.
            val progress = ((frameStartNs - spinStartNs) / 1_000_000f / plan.durationMs).coerceAtMost(1f)

            // Rotate the wheel to its eased angle for this instant into the buffer…
            renderInto(frameCanvas, targetRect, wheel, angleAt(plan, progress))
            // …then push ONLY the wheel layer. A partial update means the launcher keeps the
            // bg/frame/spin it already has and doesn't re-inflate the layout ~60×/sec — that's what
            // keeps the per-frame cross-process (Binder) cost cheap enough to look smooth. The next
            // frame overwrites this same buffer (safe — see the doc comment on the synchronous copy).
            manager.partiallyUpdateAppWidget(
                widgetId, partialViews(context) { setImageViewBitmap(R.id.wheel_image, frameBuffer) }
            )

            if (frameStats != null) {
                val frameEndNs = System.nanoTime()
                frameStats.record(
                    workMs = (frameEndNs - frameStartNs) / 1_000_000f,
                    intervalMs = if (previousPushNs == 0L) -1f else (frameEndNs - previousPushNs) / 1_000_000f,
                )
                previousPushNs = frameEndNs
            }

            if (progress >= 1f) break // we just rendered the final (landing) frame → stop

            // Pace to ~60fps WITHOUT drift: sleep the *remainder* of the frame budget after subtracting
            // the time this frame already spent rendering+pushing. coerceAtLeast(0) handles the case
            // where the work already overran the budget (then we don't sleep, just go to the next frame).
            val workMs = (System.nanoTime() - frameStartNs) / 1_000_000f
            delay((FRAME_MS - workMs).toLong().coerceAtLeast(0L).milliseconds)
        }
        frameStats?.log(spinStartNs) // MEASURE on → emit the one-line push-cadence summary for this spin
    }

    /**
     * Broadcast the landed sector index (geometric only — no prize label) so the RN bridge can relay
     * it to JS, and log it. Package-scoped; the bridge registers a NOT_EXPORTED receiver for ACTION_SPIN_RESULT.
     */
    private fun broadcastResult(context: Context, widgetId: Int, repo: WheelRepository) {
        val result = repo.lastResult()
        Log.i(TAG, "Spin landed on sector ${result.segmentIndex} @ ${result.angle}°")
        val intent = Intent(ACTION_SPIN_RESULT).apply {
            setPackage(context.packageName)
            putExtra(EXTRA_ANGLE, result.angle)
            putExtra(EXTRA_WIDGET_ID, widgetId)
            putExtra(EXTRA_SEGMENT_INDEX, result.segmentIndex)
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
        val rotated = createBitmap(src.width, src.height)
        renderInto(Canvas(rotated), Rect(0, 0, src.width, src.height), src, degrees)
        return rotated
    }

    /** Rotate [src] by [degrees] into the (reused) [canvas], clearing [targetRect] first. */
    private fun renderInto(canvas: Canvas, targetRect: Rect, src: Bitmap, degrees: Float) {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        canvas.withRotation(degrees, targetRect.width() / 2f, targetRect.height() / 2f) {
            drawBitmap(src, null, targetRect, framePaint)
        }
    }

    /** Downscale so the longest side is at most [maxPx], preserving aspect (keeps each bitmap small). */
    private fun Bitmap.capped(maxPx: Int): Bitmap {
        val longestSidePx = maxOf(width, height)
        if (longestSidePx <= maxPx) return this
        val scaleFactor = maxPx.toFloat() / longestSidePx
        return scale((width * scaleFactor).toInt(), (height * scaleFactor).toInt())
    }

    /**
     * The PendingIntent fired when the user taps the spin button. The widget's views live in the
     * launcher process, so the only way to "hear" a tap is to hand the launcher a PendingIntent that
     * it fires on our behalf — a broadcast back to THIS provider's onReceive.
     */
    private fun spinIntent(context: Context, widgetId: Int): PendingIntent {
        val intent = Intent(context, WheelSpinWidget::class.java).apply {
            action = ACTION_SPIN                                    // routed to onReceive's ACTION_SPIN branch
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId) // which widget instance was tapped
            data = "spinwheel://wheel/$widgetId".toUri()            // unique per id so PendingIntents don't collide
        }
        return PendingIntent.getBroadcast(
            context, widgetId, intent,
            // UPDATE_CURRENT: refresh extras if it already exists. IMMUTABLE: required on API 31+.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** The four decoded, size-capped layer bitmaps for one render. */
    private class Layers(val bg: Bitmap, val frame: Bitmap, val spin: Bitmap, val wheel: Bitmap)

    /**
     * Collects per-frame timing for one spin and logs a one-line summary (tag "WheelSpin").
     *
     * IMPORTANT — this measures the PRODUCER (how fast THIS process renders + pushes updates), NOT
     * the frames the launcher actually draws on screen. The real frame is composited out-of-process
     * and asynchronously by the launcher, which can coalesce or drop our updates with no feedback to
     * us — there's no cross-process frame callback to observe. So treat `fps`/`jitter`/`dropped` as a
     * "push cadence" diagnostic (is our loop keeping its budget?), not a guarantee of on-screen
     * smoothness. Read it with: `adb logcat -s WheelSpin`.
     */
    private class FrameStats(private val durationMs: Long, private val targetMs: Long) {
        private val intervalsMs = ArrayList<Float>(64)
        private var frameCount = 0
        private var workSumMs = 0f
        private var workMaxMs = 0f

        fun record(workMs: Float, intervalMs: Float) {
            frameCount++
            workSumMs += workMs
            if (workMs > workMaxMs) workMaxMs = workMs
            if (intervalMs >= 0f) intervalsMs.add(intervalMs) // first frame has no previous → skip
        }

        fun log(spinStartNs: Long) {
            val totalMs = (System.nanoTime() - spinStartNs) / 1_000_000f
            val meanIntervalMs = if (intervalsMs.isEmpty()) 0f else intervalsMs.average().toFloat()
            val variance = intervalsMs.fold(0f) { sumSqDev, interval ->
                sumSqDev + (interval - meanIntervalMs) * (interval - meanIntervalMs)
            } / intervalsMs.size.coerceAtLeast(1)
            val jitterMs = sqrt(variance)
            val maxIntervalMs = intervalsMs.maxOrNull() ?: 0f
            // A frame is "dropped" when the gap stretches past 1.5× the target budget.
            val droppedCount = intervalsMs.count { it > targetMs * 1.5f }
            val achievedFps = if (totalMs <= 0f) 0f else frameCount * 1000f / totalMs
            Log.i(
                "WheelSpin",
                "push cadence (producer-side, not displayed frames): " +
                    "frames=$frameCount fps=${"%.1f".format(achievedFps)} (target=${"%.0f".format(1000f / targetMs)}) " +
                    "interval avg=${"%.1f".format(meanIntervalMs)}ms jitter=±${"%.1f".format(jitterMs)}ms " +
                    "max=${"%.1f".format(maxIntervalMs)}ms dropped=$droppedCount " +
                    "work avg=${"%.1f".format(if (frameCount == 0) 0f else workSumMs / frameCount)}ms " +
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

        // ---- Provider internals (not part of the bridge contract) ----
        private const val TAG = "WheelSpin"
        private const val ACTION_SPIN = "com.spinwheelwidget.wheel.ACTION_SPIN_V2"

        /**
         * Square px for the wheel bitmap. 320²·4 ≈ 410KB — safely under the ~1MB per-transaction Binder
         * limit (which is also shared across in-flight transactions, hence the conservatism). Trade-off:
         * on a large placed widget (maxResize 530dp ≈ 1590px on a 3× device) the wheel is upscaled via
         * fitCenter and looks softer. There's headroom to raise this (480²·4 ≈ 920KB is still under the
         * limit) if sharper large widgets matter more than transaction margin.
         */
        private const val TARGET_PX = 320

        /**
         * Per-frame budget in ms: 16 ≈ 1000ms / 60fps. The spin loop paces itself to this. 60 is a
         * deliberate target: 30fps was tried and read as visibly steppy/not smooth on real devices for
         * this short eased spin, so we keep 60 despite the heavier per-frame IPC (see README for the
         * cost trade-off). Note this is the *push* target, not a guarantee of displayed frames.
         */
        private const val FRAME_MS = 16L

        /** Flip to false to silence the per-spin cadence log (tag "WheelSpin"). */
        private const val MEASURE = true

        // Blocking network + disk I/O → IO dispatcher (elastic pool), not Default (CPU pool).
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val framePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // One shared repository for all callbacks (built from the application context → no leak).
        @Volatile
        private var repoInstance: WheelRepository? = null

        // Widget ids with a spin currently in flight (see onReceive's gate). Thread-safe concurrent
        // set; an id is added on tap and removed when the spin finishes/throws.
        private val spinning = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()

        // Per-id "ignore taps until this elapsedRealtime()" stamp. Set when a spin ends (and slid on
        // each rejected tap) so the queued post-spin tap backlog is dropped. See onReceive's gate.
        private val cooldownUntil = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        /** How long after a spin ends taps are ignored — long enough to drain the queued backlog,
         *  short enough that a deliberate re-tap on the resting wheel still feels responsive. */
        private const val TAP_COOLDOWN_MS = 300L
    }
}
