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
import dev.folio.core.common.FolioLog
import dev.folio.core.common.PerfMonitor
import dev.folio.core.ink.erase.EraseResult
import dev.folio.core.ink.input.InputRouter
import dev.folio.core.ink.input.StylusCapabilities
import dev.folio.core.ink.input.StylusFeatures
import dev.folio.core.model.Document
import dev.folio.core.model.InkStroke
import dev.folio.core.model.Page
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
import dev.folio.core.render.viewport.ZoomBuckets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
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
 * top: [BackgroundTileLayer], [ContentTileLayer], an overlay slot for Compose, the hover ring
 * ([HoverRingView]) and the wet-ink layer (androidx.ink's `InProgressStrokesView`). Touch, hover and
 * scroll input goes through [InputRouter]: the stylus to the active tool ([StylusTools]: [PenInput] or
 * [EraserInput]; a held stylus button erases if the stylus reports buttons), hover to [HoverCursor],
 * fingers and the mouse to pan and zoom ([FingerGestures]). Erase gestures show as a preview page body until committed. Pan and
 * zoom only move existing tiles; 100 ms after the viewport settles the host requests tiles at the new
 * bucket. While attached it requests the fastest display mode ([DisplayModeHelper]). Finished pen
 * strokes go to the session through [DryHandoff]; their wet copies leave once the tiles show them.
 */
@SuppressLint("ViewConstructor") // created in code by CanvasHost only
@MainThread
@Suppress("TooManyFunctions") // view lifecycle, input, document updates and tile scheduling
class CanvasHostView internal constructor(
    context: Context,
    private val controller: CanvasController,
    afterFrameCommit: (View, Runnable) -> Unit = { view, action -> view.viewTreeObserver.registerFrameCommitCallback(action) },
    createWetSurface: (Context) -> WetSurface,
) : FrameLayout(context) {
    /** Host for [controller] with the androidx.ink wet-ink layer. */
    constructor(context: Context, controller: CanvasController) : this(context, controller, createWetSurface = { InkWetSurface(it) })

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
    private val idleRequest = Runnable { requestTiles(idle = true) }
    private val memoryCallbacks = MemoryCallbacks()
    private var document: Document? = null
    private var lastViewportChangeMs = 0L
    private var lastPanRequestMs = 0L
    private var requestedBucket = TileLayer.NO_BUCKET
    private val loading = HashSet<PageId>()
    private var settleStartNs = 0L
    private var lastVisible: List<VisiblePage> = emptyList()

    // Erase gesture shown over the document; after the commit, until the document's body of its page changes.
    private var erasePreview: EraseResult? = null
    private var eraseCommittedOn: Page? = null
    private var eraseCommitted = 0
    private var erasedStrokes = 0
    private var eraseRejected = 0

    private val wet = createWetSurface(context)
    private val pen: PenInput =
        PenInput(
            controller.viewport,
            wet,
            brush = { controller.activeBrush },
            capabilities = { router.capabilities },
            onStrokeStart = ::requestUnbufferedDispatch,
        )
    private val eraser =
        EraserInput(
            controller.viewport,
            body = { id -> document?.let { shownBody(it, id) } },
            options = { controller.eraserOptions },
            scope = scope,
            worker = controller.renderDispatcher,
            onPreview = ::showErasePreview,
            onFinished = ::commitErase,
        )
    private val tools =
        StylusTools(
            pen,
            eraser,
            buttonErases = { event -> StylusFeatures.buttonErases(event, router.capabilities, controller.stylusPreferences) },
        ) { controller.activeTool }
    private val hoverRing = HoverRingView(context)
    private val hoverCursor =
        HoverCursor(
            hoverRing,
            controller.viewport,
            enabled = { StylusFeatures.hoverCursor(router.capabilities, controller.stylusPreferences) },
            radiusPt = ::hoverRadiusPt,
            minRadiusPx = MIN_HOVER_RING_DP * resources.displayMetrics.density,
        )
    private val router: InputRouter =
        InputRouter(
            tools,
            gestures,
            largeTouchPx = InputRouter.LARGE_TOUCH_MM * resources.displayMetrics.xdpi / MM_PER_INCH,
            hover = hoverCursor,
        )
    private val handoff =
        DryHandoff(
            commit = ::commitStrokes,
            isDrawn = ::isDrawn,
            invalidate = ::invalidateLayers,
            afterFrameCommit = { afterFrameCommit(this, it) },
            removeWet = wet::remove,
        )

    /** Slot for the Compose overlay (focused text, selection, lasso, ...), above committed content. */
    val overlay = FrameLayout(context)

    /** Wet ink layer, always on top. */
    val wetLayer: View get() = wet.view

    /** Wet-ink counters. */
    val inkStats: InkStats get() = pen.stats

    /** Dry handoff counters. */
    val handoffStats: HandoffStats get() = HandoffStats(handoff.committedCount, handoff.pendingCount, handoff.removedCount)

    /** Eraser counters. */
    val eraseStats: EraseStats
        get() = EraseStats(eraser.startedCount, eraseCommitted, erasedStrokes, eraser.canceledCount + eraseRejected)

    /** What the last stylus reported. */
    val stylusCapabilities: StylusCapabilities get() = router.capabilities

    /** True while the hover cursor ring is visible. */
    val isHoverRingShown: Boolean get() = hoverRing.isRingShown

    /** Refresh rate requested on attach, Hz (null if no mode fits or not attached yet). */
    var requestedHz: Float? = null
        private set

    init {
        val match = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(background, match)
        addView(content, LayoutParams(match))
        addView(overlay, LayoutParams(match))
        addView(hoverRing, LayoutParams(match))
        addView(wetLayer, LayoutParams(match))
        wet.onFinished = handoff::onFinished
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
        val committedOn = eraseCommittedOn
        if (committedOn != null && next.pageBodies[committedOn.id] !== committedOn) {
            // The committed erase (or a later change) arrived: the document shows it now.
            erasePreview = null
            eraseCommittedOn = null
        }
        loading.removeAll(next.pageBodies.keys)
        if (previous?.pages != next.pages) layoutPages()
        val keep = HashSet<String>(next.pages.size * 2)
        for (ref in next.pages) {
            keep += ref.id.value
            backgroundTiles.setPage(ref, null)
            contentTiles.setPage(ref, shownBody(next, ref.id))
        }
        backgroundTiles.retainPages(keep)
        contentTiles.retainPages(keep)
        refreshTiles()
    }

    private fun refreshTiles() {
        invalidateLayers()
        // Mid-gesture the idle timer requests later; otherwise refresh stale or newly loaded tiles now.
        if (SystemClock.uptimeMillis() - lastViewportChangeMs >= IDLE_MS && !isAnimating) requestTiles(idle = true)
        handoff.check()
    }

    /** Body of page [id] as shown: the document's, with the erase preview applied. */
    private fun shownBody(
        doc: Document,
        id: PageId,
    ): Page? {
        val body = doc.pageBodies[id] ?: return null
        val preview = erasePreview ?: return body
        return if (preview.pageId == id) preview.applyTo(body) else body
    }

    /** Shows [result] over the document (null: the document as is); tiles under the changes re-render. */
    private fun showErasePreview(result: EraseResult?) {
        val old = erasePreview
        erasePreview = result
        eraseCommittedOn = null
        val doc = document ?: return
        for (id in setOfNotNull(old?.pageId, result?.pageId)) {
            val ref = doc.pageRef(id) ?: continue
            contentTiles.setPage(ref, shownBody(doc, id))
        }
        refreshTiles()
    }

    /**
     * Commits a finished erase gesture as one command. Its preview stays until the document's body of the
     * page changes (the command's objects are the preview's, so the tiles do not change) or the session
     * rejects it.
     */
    private fun commitErase(result: EraseResult) {
        val base = document?.pageBodies?.get(result.pageId)
        if (result.isEmpty || base == null) {
            showErasePreview(null)
            return
        }
        showErasePreview(result)
        eraseCommittedOn = base
        eraseCommitted++
        erasedStrokes += result.replacements.size
        scope.launch {
            val accepted =
                try {
                    controller.execute(result.command())
                } catch (e: IllegalStateException) {
                    FolioLog.w(TAG, "erase failed: ${e.message}", e) // session closed under the view
                    false
                }
            if (!accepted) {
                eraseRejected++
                if (erasePreview === result) showErasePreview(null)
            }
        }
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
        handoff.clear()
        scope.coroutineContext.cancelChildren()
        erasePreview = null
        eraseCommittedOn = null
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

    /** Radius of the mark the hovering pen would make, pt: the eraser's, or half the brush width. */
    private fun hoverRadiusPt(event: MotionEvent): Float {
        // HOT PATH: per hover event.
        val eraserEnd = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        return if (tools.erases(event, eraserEnd)) controller.eraserOptions.radiusPt else controller.activeBrush.sizePt / 2f
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
        val now = SystemClock.uptimeMillis()
        lastViewportChangeMs = now
        removeCallbacks(idleRequest)
        postDelayed(idleRequest, IDLE_MS)
        // Panning at the requested bucket: tiles moving into view render now instead of after the gesture.
        if (viewport.bucketIndex == requestedBucket && now - lastPanRequestMs >= PAN_REQUEST_MS) {
            lastPanRequestMs = now
            requestTiles(idle = false)
        }
    }

    private fun invalidateLayers() {
        background.invalidate()
        content.invalidate()
    }

    /**
     * Requests tiles for the visible pages at the current bucket (plus base tiles and the prefetch ring)
     * and decodes their bodies (plus neighbors). [idle]: the viewport settled (the request that may change
     * the bucket, timed as `render:settle`); otherwise a pan at the requested bucket.
     */
    private fun requestTiles(idle: Boolean) {
        val stack = viewport.layout ?: return
        val doc = document ?: return
        if (idle && isAnimating) {
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
            val shown = RectPt(l, t, r, b)
            val frame = if (canvasMode) null else stack.framePt(i)
            val page = stack.pages[i]
            visible +=
                VisiblePage(
                    page.id.value,
                    shown,
                    (centerX - originX) / scale,
                    (centerY - originY) / scale,
                    prefetchRectPt(shown, ringPt, frame),
                    baseRectPt(shown, frame),
                )
            for (j in max(0, i - 1)..min(stack.size - 1, i + 1)) addIfUnloaded(stack.pages[j].id, doc, idle, missing)
        }
        if (missing.isNotEmpty()) {
            loading += missing
            controller.loadPages(missing)
        }
        // Visible tiles of both layers queue before any prefetch tile.
        backgroundTiles.request(visible, scale)
        contentTiles.request(visible, scale)
        val baseBucket = ZoomBuckets.indexFor(viewport.fitWidthScale) - BASE_BUCKET_STEPS
        backgroundTiles.prefetch(visible, scale, baseBucket)
        contentTiles.prefetch(visible, scale, baseBucket)
        lastVisible = visible
        requestedBucket = viewport.bucketIndex
        if (idle) settleStartNs = if (backgroundTiles.isSettled && contentTiles.isSettled) 0L else PerfMonitor.clock.monotonicNs()
        if (contentTiles.pendingCount == 0) contentTiles.warmUp(visible)
        handoff.check()
    }

    /** [shown] grown by [ringPt] each way, clipped to [frame] on a fixed page (null: infinite canvas). */
    private fun prefetchRectPt(
        shown: RectPt,
        ringPt: Float,
        frame: RectPt?,
    ): RectPt {
        val ring = RectPt(shown.left - ringPt, shown.top - ringPt, shown.right + ringPt, shown.bottom + ringPt)
        if (frame == null) return ring
        return RectPt(max(frame.left, ring.left), max(frame.top, ring.top), min(frame.right, ring.right), min(frame.bottom, ring.bottom))
    }

    /** Base tiles: the whole fixed page; around the view (one view size each way) on an infinite canvas. */
    private fun baseRectPt(
        shown: RectPt,
        frame: RectPt?,
    ): RectPt =
        frame ?: RectPt(
            2 * shown.left - shown.right,
            2 * shown.top - shown.bottom,
            2 * shown.right - shown.left,
            2 * shown.bottom - shown.top,
        )

    /** Adds [id] to [missing] when its body is not decoded; pan requests leave loads in flight alone, idle requests retry them. */
    private fun addIfUnloaded(
        id: PageId,
        doc: Document,
        idle: Boolean,
        missing: MutableList<PageId>,
    ) {
        val retry = idle || id !in loading
        if (id !in doc.pageBodies && id !in missing && retry) missing += id
    }

    private fun onTileReady() {
        invalidateLayers()
        val start = settleStartNs
        if (start != 0L && backgroundTiles.isSettled && contentTiles.isSettled) {
            PerfMonitor.record(SECTION_SETTLE, PerfMonitor.clock.monotonicNs() - start)
            settleStartNs = 0L
        }
        if (contentTiles.pendingCount == 0) contentTiles.warmUp(lastVisible)
        handoff.check()
    }

    /** Hands [strokes] to the session; the synchronous part runs inside the `ink:commit` section. */
    private fun commitStrokes(
        page: PageId,
        strokes: List<InkStroke>,
        onRejected: () -> Unit,
    ) {
        scope.launch {
            val accepted =
                try {
                    controller.commitStrokes(page, strokes)
                } catch (e: IllegalStateException) {
                    FolioLog.w(TAG, "commit failed: ${e.message}", e) // session closed under the view
                    false
                }
            if (!accepted) onRejected()
        }
    }

    /**
     * True when [stroke] is in the shown document and every content tile under its on-screen part is
     * fresh (or none of it is on screen), so the next content-layer frame draws it.
     */
    private fun isDrawn(
        page: PageId,
        stroke: InkStroke,
    ): Boolean {
        val doc = document ?: return false
        val stack = viewport.layout ?: return false
        val body = doc.pageBodies[page] ?: return doc.pageRef(page) == null
        if (body.objects.none { it.id == stroke.id }) return false
        val index = stack.indexOf(page)
        val b = stroke.bounds
        var drawn = true
        viewport.forEachVisiblePage(width.toFloat(), height.toFloat()) { i, _, _, l, t, r, bottom ->
            if (i == index) {
                drawn =
                    contentTiles.isDrawn(page.value, max(l, b.left), max(t, b.top), min(r, b.right), min(bottom, b.bottom), viewport.scale)
            }
        }
        return drawn
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
        private const val MIN_HOVER_RING_DP = 3f
        private const val TAG = "CanvasHost"

        /** From the idle tile request until every visible tile of both layers is rendered. */
        const val SECTION_SETTLE = "render:settle"

        /** Viewport idle time before tiles of the new bucket are requested. */
        const val IDLE_MS = 100L

        /** Minimum time between tile requests while panning at the requested bucket. */
        const val PAN_REQUEST_MS = 50L

        // Base tiles render 4 half-octaves (1/4 of the px per pt) below the fit-width bucket.
        private const val BASE_BUCKET_STEPS = 4

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
