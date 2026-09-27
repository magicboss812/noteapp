package dev.folio.app.spikes

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.PerfMonitor
import dev.folio.core.render.DisplayModeHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Spike P01-S2: an A4 lined page with 1500 seeded strokes shown through 512 px tiles, either
 * software bitmaps (A) or RenderNodes with compositing layers (B). Finger drag pans; zoom is
 * scripted (`debugcmd.sh zoom-anim`). During gestures tiles are only transformed; 100 ms after
 * the gesture the current zoom bucket renders center-out (05-canvas-rendering.md#tiles).
 * Deleted or promoted by P01-T08.
 */
@SuppressLint("ViewConstructor") // created in code by AppDebugHooks only
@Suppress("TooManyFunctions") // spike screen: viewport, input, scripted zoom and verify in one place until P01-T08
@MainThread
internal class SpikeTilesView(
    private val activity: Activity,
    private val dispatchers: FolioDispatchers,
) : View(activity) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val cache = SpikeTileCache(dispatchers, scope, ::onTileReady)
    private val displayModes = DisplayModeHelper(activity.window)
    private val pagePaint = Paint().apply { color = PAPER_ARGB }
    private val idle = Runnable { onGestureIdle() }
    private val zoomFrame = Choreographer.FrameCallback(::onZoomFrame)
    private val sidePaddingPx = SIDE_PADDING_DP * resources.displayMetrics.density
    private val topPaddingPx = TOP_PADDING_DP * resources.displayMetrics.density
    private var lastX = 0f
    private var lastY = 0f
    private var lastRequestMs = 0L
    private var settleStartNs = 0L
    private var verifyPending = false
    private var animFrom = 1f
    private var animTo = 1f
    private var animStartNs = 0L
    private var animDurationNs = 0L

    /** Zoom as a multiple of fit-width. */
    var zoom = 1f
        private set

    /** Current px per pt; 0 until laid out. */
    var scale = 0f
        private set
    var offsetXPx = 0f
        private set
    var offsetYPx = 0f
        private set

    /** Zoom bucket tiles are rendered at; follows [scale] 100 ms after a gesture. */
    var bucket = 0
        private set
    var seedMs: Double? = null
        private set
    var strokeCount = 0
        private set
    var requestedHz: Float? = null
        private set
    var lastVerify: JsonObject? = null
        private set
    val strategy: TileStrategy get() = cache.strategy
    val tileCount: Int get() = cache.tileCount
    val pendingCount: Int get() = cache.pendingCount
    val pooledCount: Int get() = cache.pooledCount

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestedHz = display?.let(displayModes::request)
        scope.launch {
            val startNs = SystemClock.elapsedRealtimeNanos()
            val page =
                withContext(dispatchers.render) {
                    PerfMonitor.trace(SECTION_SEED) {
                        SpikePage.create(SyntheticStrokes.generate(SpikePage.WIDTH_PT, SpikePage.HEIGHT_PT))
                    }
                }
            seedMs = (SystemClock.elapsedRealtimeNanos() - startNs) / NS_PER_MS
            strokeCount = page.strokeCount
            cache.renderer = SpikeTileRenderer(page)
            settleStartNs = SystemClock.elapsedRealtimeNanos()
            requestVisible()
        }
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(zoomFrame)
        removeCallbacks(idle)
        scope.cancel()
        cache.reset()
        displayModes.release()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        if (scale == 0f) {
            scale = fitScale() * zoom
            offsetYPx = topPaddingPx
            clamp()
            bucket = TileMath.bucketIndex(scale)
        }
        requestVisible()
    }

    /** Drops all tiles and renders the visible ones again with [next]. */
    fun switchStrategy(next: TileStrategy) {
        cache.reset(next)
        settleStartNs = SystemClock.elapsedRealtimeNanos()
        requestVisible()
        invalidate()
    }

    /** Jumps to [target] zoom around the view center and re-renders at once. */
    fun jumpToZoom(target: Float) {
        Choreographer.getInstance().removeFrameCallback(zoomFrame)
        applyZoom(target)
        onGestureIdle()
    }

    /** Animates zoom [from] -> [to] around the view center over [durationMs] (smoothstep), one step per frame. */
    fun animateZoom(
        from: Float,
        to: Float,
        durationMs: Long,
    ) {
        removeCallbacks(idle)
        applyZoom(from)
        animFrom = from
        animTo = to
        animStartNs = 0L
        animDurationNs = durationMs * NS_PER_MS.toLong()
        Choreographer.getInstance().removeFrameCallback(zoomFrame)
        Choreographer.getInstance().postFrameCallback(zoomFrame)
    }

    /**
     * Snaps the scale to its bucket (1:1 tile pixels) and integer offsets, waits for the visible
     * tiles, then compares the window pixels with a direct render of the page ([lastVerify]).
     */
    fun verify() {
        applyZoom(TileMath.bucketScale(TileMath.bucketIndex(scale)) / fitScale())
        offsetXPx = offsetXPx.roundToInt().toFloat()
        offsetYPx = offsetYPx.roundToInt().toFloat()
        verifyPending = true
        lastVerify = buildJsonObject { put("state", "pending") }
        onGestureIdle()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) =
        PerfMonitor.trace(SECTION_DRAW) {
            // HOT PATH: every frame during pan and zoom; no allocation.
            canvas.drawColor(SURROUND_ARGB)
            if (scale == 0f) return@trace
            val right = offsetXPx + SpikePage.WIDTH_PT * scale
            val bottom = offsetYPx + SpikePage.HEIGHT_PT * scale
            canvas.drawRect(offsetXPx, offsetYPx, right, bottom, pagePaint)
            cache.draw(canvas, bucket, scale, offsetXPx, offsetYPx)
        }

    @SuppressLint("ClickableViewAccessibility") // spike: one-finger pan only, no click action
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // HOT PATH: per MotionEvent; no allocation.
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                removeCallbacks(idle)
            }

            MotionEvent.ACTION_MOVE -> {
                offsetXPx += event.x - lastX
                offsetYPx += event.y - lastY
                lastX = event.x
                lastY = event.y
                clamp()
                invalidate()
                val nowMs = SystemClock.uptimeMillis()
                if (nowMs - lastRequestMs >= PAN_REQUEST_INTERVAL_MS) {
                    lastRequestMs = nowMs
                    requestVisible() // same bucket while panning: render newly exposed tiles right away
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                postDelayed(idle, IDLE_MS)
            }
        }
        return true
    }

    private fun onZoomFrame(frameTimeNanos: Long) {
        if (animStartNs == 0L) animStartNs = frameTimeNanos
        val t = min(1f, (frameTimeNanos - animStartNs).toFloat() / animDurationNs)
        val eased = t * t * (3f - 2f * t)
        applyZoom(animFrom + (animTo - animFrom) * eased)
        if (t < 1f) Choreographer.getInstance().postFrameCallback(zoomFrame) else postDelayed(idle, IDLE_MS)
    }

    private fun fitScale(): Float = (width - 2 * sidePaddingPx) / SpikePage.WIDTH_PT

    /** Sets [target] zoom keeping the page point under the view center fixed (focal-point zoom). */
    private fun applyZoom(target: Float) {
        if (width == 0) return
        val next = fitScale() * target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val focusX = width / 2f
        val focusY = height / 2f
        val pageX = (focusX - offsetXPx) / scale
        val pageY = (focusY - offsetYPx) / scale
        zoom = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        scale = next
        offsetXPx = focusX - pageX * next
        offsetYPx = focusY - pageY * next
        clamp()
        invalidate()
    }

    /** Centers a page narrower than the view; otherwise allows half a screen of overscroll. */
    private fun clamp() {
        val contentW = SpikePage.WIDTH_PT * scale
        val contentH = SpikePage.HEIGHT_PT * scale
        offsetXPx =
            if (contentW <= width) (width - contentW) / 2f else offsetXPx.coerceIn(width / 2f - contentW, width / 2f)
        offsetYPx = offsetYPx.coerceIn(height / 2f - contentH, height / 2f)
    }

    private fun onGestureIdle() {
        val next = TileMath.bucketIndex(scale)
        if (next != bucket) {
            bucket = next
            settleStartNs = SystemClock.elapsedRealtimeNanos()
        }
        requestVisible()
        onTileReady()
    }

    private fun visibleRange(ring: Int): TileRange =
        TileMath.visibleTiles(
            SpikePage.WIDTH_PT,
            SpikePage.HEIGHT_PT,
            scale,
            offsetXPx,
            offsetYPx,
            width,
            height,
            TileMath.bucketScale(bucket),
            ring,
        )

    private fun requestVisible() {
        if (width == 0 || scale == 0f) return
        val toBucket = TileMath.bucketScale(bucket) / scale
        val order = TileMath.centerOut(visibleRange(ring = 1), (width / 2f - offsetXPx) * toBucket, (height / 2f - offsetYPx) * toBucket)
        cache.request(bucket, order)
    }

    /** After a tile arrives: record settle time, evict replaced tiles, run a pending verify. */
    private fun onTileReady() {
        invalidate()
        if (scale == 0f || !cache.complete(bucket, visibleRange(ring = 0))) return
        if (settleStartNs != 0L) {
            PerfMonitor.record("tiles:settle${strategy.name}", SystemClock.elapsedRealtimeNanos() - settleStartNs)
            settleStartNs = 0L
        }
        cache.evict(bucket, visibleRange(ring = 2))
        if (verifyPending) {
            verifyPending = false
            viewTreeObserver.registerFrameCommitCallback(::capture) // after the frame showing every tile
        }
    }

    private fun capture() {
        val renderer = cache.renderer ?: return
        val location = IntArray(2).also(::getLocationInWindow)
        val shot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val source = Rect(location[0], location[1], location[0] + width, location[1] + height)
        val snapshot = ViewSnapshot(strategy, scale, offsetXPx, offsetYPx)
        PixelCopy.request(activity.window, source, shot, { result ->
            if (result != PixelCopy.SUCCESS) {
                lastVerify = buildJsonObject { put("error", "PixelCopy result $result") }
                return@request
            }
            scope.launch { lastVerify = withContext(dispatchers.render) { compare(shot, renderer, snapshot) } }
        }, handler)
    }

    @WorkerThread
    private fun compare(
        shot: Bitmap,
        renderer: SpikeTileRenderer,
        at: ViewSnapshot,
    ): JsonObject {
        val reference = Bitmap.createBitmap(shot.width, shot.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(reference)
        canvas.drawColor(SURROUND_ARGB)
        val paper = Paint().apply { color = PAPER_ARGB }
        canvas.drawRect(
            at.offsetXPx,
            at.offsetYPx,
            at.offsetXPx + SpikePage.WIDTH_PT * at.scale,
            at.offsetYPx + SpikePage.HEIGHT_PT * at.scale,
            paper,
        )
        renderer.renderReference(canvas, at.scale, at.offsetXPx, at.offsetYPx)
        val diff = PixelDiff().addBitmaps(shot, reference)
        reference.recycle()
        shot.recycle()
        return buildJsonObject {
            put("strategy", at.strategy.name)
            put("scale", at.scale)
            put("pixels", diff.pixels)
            put("differingPct", diff.differingPct)
            put("meanChannelDiff", diff.meanChannelDiff)
        }
    }

    private class ViewSnapshot(
        val strategy: TileStrategy,
        val scale: Float,
        val offsetXPx: Float,
        val offsetYPx: Float,
    )

    companion object {
        const val ROUTE = "spike-tiles"
        const val SECTION_SEED = "tiles:seed"
        const val SECTION_DRAW = "tiles:draw"
        const val MIN_ZOOM = 0.25f
        const val MAX_ZOOM = 8f
        private const val IDLE_MS = 100L
        private const val PAN_REQUEST_INTERVAL_MS = 50L
        private const val SIDE_PADDING_DP = 24f
        private const val TOP_PADDING_DP = 16f
        private const val NS_PER_MS = 1_000_000.0
        private const val SURROUND_ARGB = 0xFFE9ECF1.toInt()
        private const val PAPER_ARGB = 0xFFFFFFFF.toInt()
    }
}
