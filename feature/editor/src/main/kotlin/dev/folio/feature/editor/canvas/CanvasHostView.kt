package dev.folio.feature.editor.canvas

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.annotation.MainThread
import dev.folio.core.common.PerfMonitor
import dev.folio.core.ink.input.InputRouter
import dev.folio.core.ink.input.StylusCapabilities
import dev.folio.core.model.Document
import dev.folio.core.model.PageId
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.DisplayModeHelper
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.RenderTarget
import dev.folio.core.render.tiles.BitmapPool
import dev.folio.core.render.tiles.TileGrid
import dev.folio.core.render.tiles.TileLayer
import dev.folio.core.render.tiles.VisiblePage
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.ViewportMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlin.math.max
import kotlin.math.min

/** Tile cache numbers for debug `state` (05-canvas-rendering.md#tiles). */
data class TileStats(
    /** Budget per layer, bytes. */
    val budgetBytesPerLayer: Long,
    /** Cached background entries (empty tiles included). */
    val backgroundTiles: Int,
    /** Bytes of background tiles. */
    val backgroundBytes: Long,
    /** Cached content entries (empty tiles included). */
    val contentTiles: Int,
    /** Bytes of content tiles. */
    val contentBytes: Long,
    /** Renders queued or running, both layers. */
    val pending: Int,
    /** Tiles evicted by the budget, both layers. */
    val evictions: Int,
)

/**
 * The editor canvas (05-canvas-rendering.md#layers), hosted by an `AndroidView`. Children bottom to
 * top: [BackgroundTileLayer], [ContentTileLayer], an overlay slot for Compose, and the wet-ink layer
 * (androidx.ink's `InProgressStrokesView`). Touch, hover and scroll input goes through [InputRouter]:
 * the stylus to the pen ([PenInput]), fingers and the mouse to pan and zoom ([FingerGestures]). Pan and
 * zoom only move existing tiles; 100 ms after the viewport settles the host requests tiles at the new
 * bucket. While attached it requests the fastest display mode ([DisplayModeHelper]).
 */
@SuppressLint("ViewConstructor") // created in code by CanvasHost only
@MainThread
@Suppress("TooManyFunctions") // view lifecycle, input, document updates and tile scheduling
class CanvasHostView internal constructor(
    context: Context,
    private val controller: CanvasController,
    createWetSurface: (Context) -> WetSurface,
) : FrameLayout(context) {
    /** Host for [controller] with the androidx.ink wet-ink layer. */
    constructor(context: Context, controller: CanvasController) : this(context, controller, { InkWetSurface(it) })

    private val viewport: Viewport get() = controller.viewport
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pool = BitmapPool()
    private val budgetPerLayer = tileBudgetBytes(context) / 2
    private val backgroundTiles = tileLayer(RenderTarget.SCREEN_BACKGROUND)
    private val contentTiles = tileLayer(RenderTarget.SCREEN_CONTENT)
    private val background = BackgroundTileLayer(context, controller.viewport, backgroundTiles)
    private val content = ContentTileLayer(context, controller.viewport, contentTiles)
    private val displayModes = context.findActivity()?.window?.let(::DisplayModeHelper)
    private val gestures =
        ViewConfiguration.get(context).let { config ->
            FingerGestures(
                controller.viewport,
                OverScroller(context),
                GestureConfig(
                    config.scaledTouchSlop.toFloat(),
                    config.scaledMinimumFlingVelocity.toFloat(),
                    config.scaledMaximumFlingVelocity.toFloat(),
                    config.scaledVerticalScrollFactor,
                ),
                onChanged = ::onViewportChanged,
                postFrame = ::postOnAnimation,
            )
        }
    private val animator = ViewportAnimator(controller.viewport, ::onViewportChanged)
    private val idleRequest = Runnable { requestTiles() }
    private val memoryCallbacks = MemoryCallbacks()
    private var document: Document? = null
    private var lastViewportChangeMs = 0L
    private var settleStartNs = 0L
    private var lastVisible: List<VisiblePage> = emptyList()

    private val wet = createWetSurface(context)
    private val pen: PenInput =
        PenInput(
            controller.viewport,
            wet,
            brush = { controller.activeBrush },
            capabilities = { router.capabilities },
            onStrokeStart = ::requestUnbufferedDispatch,
        )
    private val router: InputRouter =
        InputRouter(pen, gestures, largeTouchPx = InputRouter.LARGE_TOUCH_MM * resources.displayMetrics.xdpi / MM_PER_INCH)

    /** Slot for the Compose overlay (focused text, selection, lasso, ...), above committed content. */
    val overlay = FrameLayout(context)

    /** Wet ink layer, always on top. */
    val wetLayer: View get() = wet.view

    /** Wet-ink counters. */
    val inkStats: InkStats get() = pen.stats

    /** What the last stylus reported. */
    val stylusCapabilities: StylusCapabilities get() = router.capabilities

    /** Refresh rate requested on attach, Hz (null if no mode fits or not attached yet). */
    var requestedHz: Float? = null
        private set

    init {
        val match = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(background, match)
        addView(content, LayoutParams(match))
        addView(overlay, LayoutParams(match))
        addView(wetLayer, LayoutParams(match))
    }

    /** Current tile cache numbers. */
    val tileStats: TileStats
        get() =
            TileStats(
                budgetBytesPerLayer = budgetPerLayer,
                backgroundTiles = backgroundTiles.tileCount,
                backgroundBytes = backgroundTiles.bytes,
                contentTiles = contentTiles.tileCount,
                contentBytes = contentTiles.bytes,
                pending = backgroundTiles.pendingCount + contentTiles.pendingCount,
                evictions = backgroundTiles.evictions + contentTiles.evictions,
            )

    /**
     * Shows [next]: lays out its pages (keeping the viewport position) and hands page summaries and
     * loaded bodies to the tile layers, which invalidate what changed.
     */
    fun setDocument(next: Document) {
        val previous = document
        if (previous === next) return
        document = next
        if (previous?.pages != next.pages) layoutPages()
        val keep = HashSet<String>(next.pages.size * 2)
        for (ref in next.pages) {
            keep += ref.id.value
            backgroundTiles.setPage(ref, null)
            contentTiles.setPage(ref, next.pageBodies[ref.id])
        }
        backgroundTiles.retainPages(keep)
        contentTiles.retainPages(keep)
        invalidateLayers()
        // Mid-gesture the idle timer requests later; otherwise refresh stale or newly loaded tiles now.
        if (SystemClock.uptimeMillis() - lastViewportChangeMs >= IDLE_MS && !isAnimating) requestTiles()
    }

    /** Animates the zoom (times fit-width) [fromZoom] -> [toZoom] over [durationMs] around the view center. */
    fun animateZoom(
        fromZoom: Float,
        toZoom: Float,
        durationMs: Long,
    ) {
        if (viewport.layout == null) return
        gestures.stopFling()
        animator.zoom(fromZoom, toZoom, durationMs)
    }

    /** Scrolls to stack page [index] (0-based) over [durationMs]; 0 jumps. */
    fun scrollToPage(
        index: Int,
        durationMs: Long,
    ) {
        val stack = viewport.layout ?: return
        require(index in 0 until stack.size) { "page $index outside 0..${stack.size - 1}" }
        gestures.stopFling()
        animator.scrollToPage(index, durationMs)
    }

    /** True while a fling or a scripted animation moves the viewport. */
    val isAnimating: Boolean get() = gestures.isFlinging || animator.isRunning

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestedHz = display?.let { d -> displayModes?.request(d) }
        context.registerComponentCallbacks(memoryCallbacks)
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        gestures.release()
        displayModes?.release()
        context.unregisterComponentCallbacks(memoryCallbacks)
        removeCallbacks(idleRequest)
        scope.coroutineContext.cancelChildren()
        backgroundTiles.clear()
        contentTiles.clear()
        pool.clear()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int,
    ) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutPages()
    }

    @SuppressLint("ClickableViewAccessibility") // no click semantics: fingers only pan and zoom (06-ink-input.md)
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // HOT PATH: per MotionEvent; routing and forwarding only.
        val section = if (event.isFromSource(InputDevice.SOURCE_STYLUS)) SECTION_INK_TOUCH else SECTION_TOUCH
        PerfMonitor.trace(section) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                animator.cancel()
                gestures.stopFling()
            }
            router.onTouchEvent(event)
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // HOT PATH: per hover event; children still see hover and scroll.
        val routed = router.onGenericMotionEvent(event)
        return super.dispatchGenericMotionEvent(event) || routed
    }

    private fun layoutPages() {
        val current = document?.pages ?: return
        if (width == 0 || height == 0) return
        viewport.setPages(current, width.toFloat(), height.toFloat())
        onViewportChanged()
    }

    private fun onViewportChanged() {
        // HOT PATH: per gesture event and animation frame.
        invalidateLayers()
        lastViewportChangeMs = SystemClock.uptimeMillis()
        removeCallbacks(idleRequest)
        postDelayed(idleRequest, IDLE_MS)
    }

    private fun invalidateLayers() {
        background.invalidate()
        content.invalidate()
    }

    /** Requests tiles for the visible pages at the current bucket and decodes their bodies (plus neighbors). */
    private fun requestTiles() {
        val stack = viewport.layout ?: return
        val doc = document ?: return
        if (isAnimating) {
            postDelayed(idleRequest, IDLE_MS)
            return
        }
        val visible = ArrayList<VisiblePage>()
        val missing = ArrayList<PageId>()
        val scale = viewport.scale
        val ringPt = TileGrid.tileSizePt(viewport.bucketIndex)
        val centerX = width / 2f
        val centerY = height / 2f
        val canvasMode = viewport.mode is ViewportMode.Canvas
        viewport.forEachVisiblePage(width.toFloat(), height.toFloat()) { i, originX, originY, l, t, r, b ->
            val frame = stack.framePt(i)
            val prefetch =
                if (canvasMode) {
                    RectPt(l - ringPt, t - ringPt, r + ringPt, b + ringPt)
                } else {
                    RectPt(
                        max(frame.left, l - ringPt),
                        max(frame.top, t - ringPt),
                        min(frame.right, r + ringPt),
                        min(
                            frame.bottom,
                            b + ringPt,
                        ),
                    )
                }
            val page = stack.pages[i]
            visible += VisiblePage(page.id.value, RectPt(l, t, r, b), (centerX - originX) / scale, (centerY - originY) / scale, prefetch)
            for (j in max(0, i - 1)..min(stack.size - 1, i + 1)) {
                val id = stack.pages[j].id
                if (id !in doc.pageBodies && id !in missing) missing += id
            }
        }
        if (missing.isNotEmpty()) controller.loadPages(missing)
        // Visible tiles of both layers queue before any prefetch tile.
        backgroundTiles.request(visible, scale)
        contentTiles.request(visible, scale)
        backgroundTiles.prefetch(visible, scale)
        contentTiles.prefetch(visible, scale)
        lastVisible = visible
        settleStartNs = if (backgroundTiles.isSettled && contentTiles.isSettled) 0L else PerfMonitor.clock.monotonicNs()
        if (contentTiles.pendingCount == 0) contentTiles.warmUp(visible)
    }

    private fun onTileReady() {
        invalidateLayers()
        val start = settleStartNs
        if (start != 0L && backgroundTiles.isSettled && contentTiles.isSettled) {
            PerfMonitor.record(SECTION_SETTLE, PerfMonitor.clock.monotonicNs() - start)
            settleStartNs = 0L
        }
        if (contentTiles.pendingCount == 0) contentTiles.warmUp(lastVisible)
    }

    private fun tileLayer(target: RenderTarget) =
        TileLayer(target, budgetPerLayer, pool, scope, controller.renderDispatcher, ::PageRenderer, ::onTileReady)

    private inner class MemoryCallbacks : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            backgroundTiles.trimMemory()
            contentTiles.trimMemory()
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
    }

    /** PerfMonitor sections and timing. */
    companion object {
        /** Time spent routing one finger or mouse MotionEvent. */
        const val SECTION_TOUCH = "canvas:touch"

        /** Time spent routing and forwarding one stylus MotionEvent to wet ink (R-PERF-03). */
        const val SECTION_INK_TOUCH = "ink:onTouch"

        private const val MM_PER_INCH = 25.4f

        /** From the idle tile request until every visible tile of both layers is rendered. */
        const val SECTION_SETTLE = "render:settle"

        /** Viewport idle time before tiles of the new bucket are requested. */
        const val IDLE_MS = 100L

        /** Share of `largeMemoryClass` for both tile caches (05-canvas-rendering.md#tiles). */
        private const val BUDGET_SHARE = 4
        private const val BYTES_PER_MB = 1024L * 1024L

        private fun tileBudgetBytes(context: Context): Long {
            val reported = context.getSystemService(ActivityManager::class.java)?.largeMemoryClass ?: 0
            val mb = if (reported > 0) reported else DEFAULT_LARGE_MEMORY_MB
            return mb * BYTES_PER_MB / BUDGET_SHARE
        }

        private const val DEFAULT_LARGE_MEMORY_MB = 512
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
