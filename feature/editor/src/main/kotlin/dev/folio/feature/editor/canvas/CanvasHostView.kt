package dev.folio.feature.editor.canvas

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.PointerIcon
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
import dev.folio.core.ink.shape.DwellDetector
import dev.folio.core.model.Document
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.Shape
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.SelectionEdit
import dev.folio.core.model.edit.UpdateObjects
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.DisplayModeHelper
import dev.folio.core.render.PageContent
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.RenderTarget
import dev.folio.core.render.tiles.BitmapPool
import dev.folio.core.render.tiles.TileGrid
import dev.folio.core.render.tiles.TileLayer
import dev.folio.core.render.tiles.VisiblePage
import dev.folio.core.render.viewport.PointPx
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.ViewportMode
import dev.folio.core.render.viewport.ZoomBuckets
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.render.viewport.toPagePt
import dev.folio.core.text.flow.PageText
import dev.folio.feature.editor.text.CanvasText
import dev.folio.feature.editor.text.TextFieldSpec
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

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
@Suppress("TooManyFunctions", "LargeClass") // view lifecycle, input, document updates and tile scheduling; helpers own the rest
class CanvasHostView internal constructor(
    context: Context,
    private val controller: CanvasController,
    createWetSurface: (Context) -> WetSurface,
) : FrameLayout(context) {
    /** Host for [controller] with the androidx.ink wet-ink layer. */
    constructor(context: Context, controller: CanvasController) : this(context, controller, createWetSurface = { InkWetSurface(it) })

    private val viewport: Viewport get() = controller.viewport
    private val scope = CoroutineScope(SupervisorJob() + controller.mainDispatcher)
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
                onTap = { x, y -> onFingerTap(x, y).also { touchCounts.fingerTaps++ } },
            )
        }
    private val animator = ViewportAnimator(controller.viewport, ::onViewportChanged)
    private val idleRequest =
        Runnable {
            requestTiles(idle = true)
            textField.refresh() // the field takes its real font size at the settled zoom
        }
    private val memoryCallbacks = MemoryCallbacks()
    private var document: Document? = null
    private var pageTexts: Map<PageId, PageText> = emptyMap()
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

    /** Hold to snap for the pen (06-ink-input.md#shape-recognition; tests drive its holds). */
    internal val snap =
        PenSnap(
            DwellDetector.RADIUS_DP * resources.displayMetrics.density,
            MIN_SNAP_DP * resources.displayMetrics.density,
            object : HoldTimer {
                override fun nowMs(): Long = SystemClock.uptimeMillis()

                override fun post(
                    task: Runnable,
                    delayMs: Long,
                ) {
                    removeCallbacks(task)
                    postDelayed(task, delayMs)
                }

                override fun cancel(task: Runnable) {
                    removeCallbacks(task)
                }
            },
        )
    private val pen: PenInput =
        PenInput(
            controller.viewport,
            wet,
            brush = { controller.activeBrush },
            capabilities = { router.capabilities },
            onStrokeStart = ::requestUnbufferedDispatch,
            snap = snap,
            straightOnly = { controller.alwaysStraight },
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

    /** The Shape tool's live shape (tests read what it shows). */
    internal val shapePreview = ShapePreview(context, controller.viewport)
    private val shapeInput =
        ShapeInput(
            controller.viewport,
            pageIdAt = { index -> document?.pages?.getOrNull(index)?.id },
            objectsOn = { id -> document?.pageBodies?.get(id)?.objects },
            spec = { controller.activeShape },
            sizes =
                resources.displayMetrics.density.let { d ->
                    ShapeInputSizes(
                        POLYGON_CLOSE_DP * d,
                        SHAPE_HANDLE_DP * d,
                        SHAPE_ROTATE_OFFSET_DP * d,
                        ViewConfiguration.getDoubleTapTimeout().toLong(),
                    )
                },
            invalidate = { shapePreview.invalidate() },
            onHide = ::hideShape,
            onCommit = ::commitShape,
        ).also { shapePreview.input = it }

    // The shape a Shape tool drag edits: the tiles leave it out while the preview draws it (P07-T01).
    private var hiddenShape: HiddenShape? = null

    /** The lasso's path, selection box and action bar (tests read what it shows). */
    internal val lassoOverlay = LassoOverlay(context, controller.viewport)

    // The lasso selection a drag moves: the tiles leave it out while the page body is [LassoHidden.before] (P07-T03).
    private var lassoHidden: LassoHidden? = null
    private val snapshotRenderer by lazy(LazyThreadSafetyMode.NONE) { PageRenderer(images = controller.images) }

    /** The lasso (tests drive it). */
    internal val lasso =
        LassoInput(
            controller.viewport,
            pageIdAt = { index -> document?.pages?.getOrNull(index)?.id },
            indexOf = { id -> viewport.layout?.indexOf(id) ?: -1 },
            objectsOn = { id -> document?.pageBodies?.get(id)?.objects },
            spec = { controller.lassoSpec },
            clipboard = { controller.clipboard },
            sizes =
                resources.displayMetrics.density.let { d ->
                    LassoSizes(
                        SHAPE_HANDLE_DP * d,
                        SHAPE_ROTATE_OFFSET_DP * d,
                        LASSO_MIN_SIDE_DP * d,
                        ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
                    )
                },
            host = LassoBridge(),
            toolbar = LassoToolbar(resources.displayMetrics.density),
        ).also { lassoOverlay.input = it }
    private val tools =
        StylusTools(
            pen,
            eraser,
            buttonErases = { event -> StylusFeatures.buttonErases(event, router.capabilities, controller.stylusPreferences) },
            navigate = StylusNavigation(gestures),
            text = StylusTap(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), TEXT_TAP_TIMEOUT_MS, ::onTextToolTap),
            shape = shapeInput,
            lasso = lasso,
        ) { controller.activeTool }
    private val hoverRing = HoverRingView(context)
    private val hoverCursor =
        HoverCursor(
            hoverRing,
            controller.viewport,
            enabled = {
                controller.activeTool != CanvasTool.NAVIGATE &&
                    controller.activeTool != CanvasTool.TEXT &&
                    controller.activeTool != CanvasTool.LASSO &&
                    StylusFeatures.hoverCursor(router.capabilities, controller.stylusPreferences)
            },
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
            removeWet = wet::remove,
        )

    /** Slot for the Compose overlay (focused text, selection, lasso, ...), above committed content. */
    val overlay = FrameLayout(context)

    private val textField =
        TextFieldHost(
            context,
            overlay,
            controller.viewport,
            controller,
            isDrawn = { contentTiles.drawsBlockOf(it, controller.viewport, width.toFloat(), height.toFloat()) },
            invalidateTiles = ::invalidateLayers,
            onCaretChanged = { caret.check() },
        ) { document }

    /** The selection boxes and handles above the live field (tests read what it shows). */
    internal val selectionView = SelectionOverlay(context, controller.viewport, controller) { document }

    // Finger text selection; its menu runs the live field's clipboard paths (07-text-engine.md#selection-as-built).
    private val selectionGestures =
        SelectionGestures(this, controller, selectionView, { document }, gestures::cancelNavigation, router::onTouchEvent) { id ->
            textField.liveField?.onTextContextMenuItem(id)
        }

    /** The dashed outlines of the text boxes while the Text tool is active (tests read what it shows). */
    internal val boxOutlines = BoxOutlines(context, controller.viewport) { document }

    /** The outline, grip, knobs and toolbar of the selected text box (tests read what it shows). */
    internal val boxHandles =
        BoxHandles(
            context,
            controller.viewport,
            controller,
            { document },
            BoxHost(
                runsOf = { page, frame -> pageTexts[page]?.runsOf(frame).orEmpty() },
                textArgb = { page ->
                    document
                        ?.page(page)
                        ?.background
                        ?.paperArgb
                        ?.let(PageRenderer::textArgb) ?: BOX_TEXT_ARGB
                },
                hide = ::hideBoxText,
                isDrawn = ::isTextDrawn,
                tap = { x, y, stylus -> if (stylus) onTextToolTap(x, y) else onFingerTap(x, y) },
            ),
        )

    // Frames of a box being moved: the tiles leave their text out while the box chrome draws it (P07-T13).
    private var hiddenFrames: Set<ObjectId> = emptySet()

    /** The live text field once a block was focused, null before (tests and debug automation read its state). */
    internal val liveTextField: LiveTextField? get() = textField.liveField

    // Arrow keys move the box in Preview (P07-T14); the caret keeper keeps the moving box in view.
    internal val boxKeys =
        BoxKeyMover(controller.viewport, resources.displayMetrics.density, boxHandles, { document }) { caret.check() }

    /**
     * Keeps the caret line (or a box that arrow keys move) above the keyboard and the toolbox; CanvasHost sets what the
     * chrome covers. A key move follows at once, frame by frame; the caret scrolls animated.
     */
    internal val caret: CaretKeeper =
        CaretKeeper(this, CARET_MARGIN_DP * resources.displayMetrics.density, { boxKeys.lineOf(it) || textField.caretLinePx(it) }) {
            animator.scrollBy(it, if (boxKeys.active) 0L else KEY_ANIMATION_MS)
        }

    /** The live field's host (tests read the tiles-to-field handoff, P06-T18). */
    internal val textFields: TextFieldHost get() = textField

    /** Runs the editor shortcut for a Ctrl or Alt chord typed in the live text field; true if one ran. */
    var onShortcut: (keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean) -> Boolean
        get() = textField.onShortcut
        set(value) {
            textField.onShortcut = value
        }

    /** Wet ink layer, always on top. */
    val wetLayer: View get() = wet.view

    /** Wet-ink counters. */
    val inkStats: InkStats get() = pen.stats

    /** Pointer downs and taps that reached this view (debug automation). */
    val touchCounts = TouchCounts { textField.caretTaps }

    /** The live field and the edited-text overlay in one line (debug automation). */
    val textOverlayState: String get() = textField.describe()

    /** Lasso selection for debug `state`: selected count, box (pt, rounded) and whether a commit is pending. */
    val lassoState: String
        get() {
            val box = lasso.box() ?: return "none"
            return "selected=${lasso.selection?.ids?.size} box=${box.left.roundToInt()},${box.top.roundToInt()}," +
                "${box.right.roundToInt()},${box.bottom.roundToInt()} pending=${lasso.pending != null}"
        }

    /** Dry handoff counters. */
    val handoffStats: HandoffStats get() = HandoffStats(handoff.committedCount, handoff.pendingCount, handoff.removedCount)

    /** Eraser counters. */
    val eraseStats: EraseStats
        get() = EraseStats(eraser.startedCount, eraseCommitted, erasedStrokes, eraser.canceledCount + eraseRejected)

    /** What the last stylus reported. */
    val stylusCapabilities: StylusCapabilities get() = router.capabilities

    /** True while the hover cursor ring is visible. */
    val isHoverRingShown: Boolean get() = hoverRing.isRingShown

    /** Hover events that reached this view (debug `state`: tells "no hover arrives" from "ring not seen"). */
    var hoverEventCount = 0
        private set

    private val noPointerIcon = PointerIcon.getSystemIcon(context, PointerIcon.TYPE_NULL)

    /** Refresh rate requested on attach, Hz (null if no mode fits or not attached yet). */
    var requestedHz: Float? = null
        private set

    init {
        val match = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(background, match)
        addView(content, LayoutParams(match))
        addView(boxOutlines, LayoutParams(match))
        addView(shapePreview, LayoutParams(match))
        addView(lassoOverlay, LayoutParams(match))
        addView(overlay, LayoutParams(match))
        addView(selectionView, LayoutParams(match))
        addView(boxHandles, LayoutParams(match))
        addView(hoverRing, LayoutParams(match))
        addView(wetLayer, LayoutParams(match))
        wet.onFinished = handoff::onFinished
        shapePreview.snap = snap
        snap.onChanged = shapePreview::invalidate
        snap.onFinished = ::commitSnap
        contentDescription = CONTENT_DESCRIPTION
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
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
        // The jump target arrives with the first document after the jump; a page that is not in it is gone.
        pendingJump?.let { if (next.pageRef(it) != null) goToPage(it) else pendingJump = null }
        val keep = HashSet<String>(next.pages.size * 2)
        for (ref in next.pages) {
            keep += ref.id.value
            backgroundTiles.setPage(ref, null)
            contentTiles.setPage(ref, shownBody(next, ref.id), textOf(ref.id))
        }
        backgroundTiles.retainPages(keep)
        contentTiles.retainPages(keep)
        refreshTiles()
        textField.refresh()
        if (boxOutlines.shown.isNotEmpty()) boxOutlines.invalidate()
        if (shapeInput.selectionPage >= 0) shapePreview.invalidate() // undo may move or remove the selected shape
        pendingSelection?.let { selectObjects(it) }
        if (lasso.showing) {
            lasso.check()
            lassoOverlay.invalidate() // undo may move or remove the selection
        }
    }

    // A selection asked for before the document with its objects arrived.
    private var pendingSelection: ObjectSelection? = null

    /** Selects [selection] with the lasso (an inserted image) once the document shows its objects. */
    @MainThread
    fun selectObjects(selection: ObjectSelection) {
        val objects = document?.pageBodies?.get(selection.pageId)?.objects
        val ids = selection.ids.toHashSet()
        if (objects == null || objects.count { it.id in ids } < ids.size) {
            pendingSelection = selection
            return
        }
        pendingSelection = null
        lasso.select(LassoSelection(selection.pageId, selection.ids))
    }

    /**
     * Shows [next] (computed off the main thread): tiles under changed page text re-render, and the live field shows
     * the focused block the pages leave out, handed over from and to the tiles without a doubled or empty frame.
     */
    fun setCanvasText(next: CanvasText) {
        val previous = pageTexts
        if (previous !== next.pages) {
            pageTexts = next.pages
            val doc = document
            if (doc != null) {
                for (ref in doc.pages) {
                    if (previous[ref.id] !== next.pages[ref.id]) contentTiles.setPage(ref, shownBody(doc, ref.id), textOf(ref.id))
                }
                refreshTiles()
            }
        }
        textField.show(next.field)
        selectionView.show(next.selection)
        boxHandles.show(next.box)
        boxOutlines.show(next.outlines)
    }

    private fun textOf(id: PageId): PageText {
        val text = pageTexts[id] ?: return PageText.EMPTY
        return if (hiddenFrames.isEmpty()) text else text.without(hiddenFrames)
    }

    // The tiles leave out the text of [frames] (a box the chrome draws while it moves); an empty set shows all again.
    private fun hideBoxText(frames: Set<ObjectId>) {
        if (frames == hiddenFrames) return
        val before = hiddenFrames
        hiddenFrames = frames
        val doc = document ?: return
        for (ref in doc.pages) {
            val runs = pageTexts[ref.id]?.runs ?: continue
            if (runs.any { it.frameId in before || it.frameId in frames }) contentTiles.setPage(ref, shownBody(doc, ref.id), textOf(ref.id))
        }
        refreshTiles()
    }

    // True when the content tiles under [rect] of page [page] (the visible part) are drawn and fresh.
    private fun isTextDrawn(
        page: PageId,
        rect: RectPt,
    ): Boolean {
        val stack = viewport.layout ?: return true
        val index = stack.indexOf(page)
        var drawn = true
        viewport.forEachVisiblePage(width.toFloat(), height.toFloat()) { i, _, _, l, t, r, b ->
            if (i == index) {
                drawn =
                    contentTiles.isDrawn(
                        page.value,
                        max(l, rect.left),
                        max(t, rect.top),
                        min(r, rect.right),
                        min(b, rect.bottom),
                        viewport.scale,
                    )
            }
        }
        return drawn
    }

    private fun refreshTiles() {
        invalidateLayers()
        // Changed content renders now, mid-gesture too while the bucket holds (a focused block's tiles must not wait for
        // the keyboard's resize to settle); a new bucket waits for the idle timer.
        val idle = SystemClock.uptimeMillis() - lastViewportChangeMs >= IDLE_MS && !isAnimating
        if (idle || viewport.bucketIndex == requestedBucket) requestTiles(idle)
        handoff.check()
        textField.check()
    }

    /** Body of page [id] as shown: the document's, with the erase preview applied. */
    private fun shownBody(
        doc: Document,
        id: PageId,
    ): Page? {
        val stored = doc.pageBodies[id] ?: return null
        val hidden = hiddenShape
        val body =
            if (hidden != null &&
                hidden.hidesIn(stored)
            ) {
                stored.copy(objects = stored.objects.removeAll { it.id == hidden.id })
            } else {
                stored
            }
        val lassoed =
            lassoHidden?.takeIf { it.pageId == id && it.before === stored }?.let { h ->
                body.copy(
                    objects =
                        body.objects.removeAll {
                            it.id in
                                h.ids
                        },
                )
            }
                ?: body
        val preview = erasePreview ?: return lassoed
        return if (preview.pageId == id) preview.applyTo(lassoed) else lassoed
    }

    // The tiles leave [selection] out while its page body stays the one shown now (the drag's commit replaces it).
    private fun hideLassoed(selection: LassoSelection?) {
        val old = lassoHidden
        val doc = document ?: return
        val next = selection?.let { sel -> doc.pageBodies[sel.pageId]?.let { LassoHidden(sel.pageId, sel.ids.toHashSet(), it) } }
        if (old?.pageId == next?.pageId && old?.ids == next?.ids && old?.before === next?.before) return
        lassoHidden = next
        for (id in setOfNotNull(old?.pageId, next?.pageId)) {
            val ref = doc.pageRef(id) ?: continue
            contentTiles.setPage(ref, shownBody(doc, id), textOf(id))
        }
        refreshTiles()
    }

    /** The canvas side of the lasso: tiles, snapshots, commits (P07-T03). */
    private inner class LassoBridge : LassoHost {
        override fun invalidate() = lassoOverlay.invalidate()

        override fun hide(selection: LassoSelection?) = hideLassoed(selection)

        override fun snapshot(
            selection: LassoSelection,
            box: RectPt,
        ): LassoSnapshot? {
            val doc = document ?: return null
            val ref = doc.pageRef(selection.pageId) ?: return null
            val body = doc.pageBodies[selection.pageId] ?: return null
            val ids = selection.ids.toHashSet()
            val region = box.inset(-SNAPSHOT_MARGIN_PT)
            if (region.isEmpty) return null
            val scale = min(viewport.scale, MAX_SNAPSHOT_PX / max(region.widthPt, region.heightPt))
            val bitmap =
                Bitmap.createBitmap(
                    ceil(region.widthPt * scale).toInt().coerceAtLeast(1),
                    ceil(region.heightPt * scale).toInt().coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888,
                )
            val page = body.copy(objects = body.objects.removeAll { it.id !in ids })
            PerfMonitor.trace(SECTION_LASSO_SNAPSHOT) {
                snapshotRenderer.draw(
                    Canvas(bitmap),
                    ref,
                    PageContent.of(page, textOf(selection.pageId)),
                    region,
                    scale,
                    RenderTarget.SCREEN_CONTENT,
                )
            }
            return LassoSnapshot(bitmap, region, scale)
        }

        override fun edit(
            selection: LassoSelection,
            edit: SelectionEdit,
            done: (Boolean) -> Unit,
        ) {
            scope.launch {
                val accepted =
                    try {
                        withContext(NonCancellable) { controller.editSelection(selection.pageId, selection.ids, edit) }
                    } catch (e: IllegalStateException) {
                        FolioLog.w(TAG, "lasso edit failed: ${e.message}", e) // session closed under the view
                        false
                    }
                done(accepted)
            }
        }

        override fun copyImage(
            pageId: PageId,
            objects: List<PageObject>,
        ) {
            val doc = document ?: return
            val ref = doc.pageRef(pageId) ?: return
            val body = doc.pageBodies[pageId] ?: return
            val page = body.copy(objects = objects.toPersistentList())
            val text = textOf(pageId)
            scope.launch {
                val file =
                    withContext(controller.renderDispatcher) { SelectionImage.render(context, ref, page, text, controller.images) }
                        ?: return@launch
                SelectionImage.publish(context, file)
            }
        }

        override fun editImage(
            pageId: PageId,
            id: ObjectId,
        ) = controller.editImage(pageId, id)

        override fun isDrawn(
            pageId: PageId,
            rect: RectPt,
        ): Boolean = isTextDrawn(pageId, rect)
    }

    private fun hideShape(next: HiddenShape?) {
        val old = hiddenShape
        if (old == next) return
        hiddenShape = next
        val doc = document ?: return
        for (id in setOfNotNull(old?.pageId, next?.pageId)) {
            val ref = doc.pageRef(id) ?: continue
            contentTiles.setPage(ref, shownBody(doc, id), textOf(id))
        }
        refreshTiles()
    }

    /** Shows [result] over the document (null: the document as is); tiles under the changes re-render. */
    private fun showErasePreview(result: EraseResult?) {
        val old = erasePreview
        erasePreview = result
        eraseCommittedOn = null
        val doc = document ?: return
        for (id in setOfNotNull(old?.pageId, result?.pageId)) {
            val ref = doc.pageRef(id) ?: continue
            contentTiles.setPage(ref, shownBody(doc, id), textOf(id))
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
                    withContext(NonCancellable) { controller.execute(result.command()) } // survives a detach
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

    // A jump to a page the stack does not hold yet (just inserted) waits for the document that has it.
    private var pendingJump: PageId? = null

    /** Scrolls to page [id] over [durationMs]; if the page is not laid out yet, once the document with it arrives. */
    fun goToPage(
        id: PageId,
        durationMs: Long = PAGE_JUMP_MS,
    ) {
        val stack = viewport.layout
        val index = stack?.indexOf(id) ?: -1
        if (index < 0) {
            pendingJump = id
            return
        }
        pendingJump = null
        if (viewport.mode is ViewportMode.Stack) scrollToPage(index, durationMs)
    }

    /** Runs a viewport [command] (keyboard shortcuts); page commands apply to the page stack only. */
    internal fun perform(command: CanvasCommand) {
        if (viewport.layout == null) return
        val zoom = viewport.zoom
        val toZoom = command.zoomFrom(zoom, KEY_ZOOM_STEP)
        if (toZoom != null) {
            animateZoom(zoom, toZoom, KEY_ANIMATION_MS)
        } else {
            val target = viewport.pageTargetOf(command)
            if (target >= 0) scrollToPage(target, KEY_ANIMATION_MS)
        }
    }

    /** True while a fling or a scripted animation moves the viewport. */
    val isAnimating: Boolean get() = gestures.isFlinging || animator.isRunning

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestedHz = display?.let { d -> displayModes?.request(d) }
        context.registerComponentCallbacks(memoryCallbacks)
        Looper.myQueue().addIdleHandler { false.also { textField.prepare() } }
    }

    override fun onDetachedFromWindow() {
        boxKeys.stop()
        animator.cancel()
        gestures.release()
        displayModes?.release()
        context.unregisterComponentCallbacks(memoryCallbacks)
        removeCallbacks(idleRequest)
        selectionGestures.cancel()
        handoff.clear()
        snap.stop()
        snap.committed() // its commit still runs; a reattached view draws it from the tiles
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
                touchCounts.down(event)
                animator.cancel()
                gestures.stopFling()
                yieldTextToStylus(event)
                if (controller.activeTool != CanvasTool.SHAPE) shapeInput.cancelDraft()
                if (controller.activeTool != CanvasTool.LASSO && lasso.showing) lasso.clear()
            }
            // A finger on the lasso selection or its action bar moves it or presses there instead of panning.
            val finger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
            if (finger && controller.activeTool == CanvasTool.LASSO && lasso.onFingerTouch(event)) return@trace
            // A finger or the pen on a box's grip or width handle moves or resizes the box, a press on the selection menu
            // runs it, a finger on a selection handle drags that end, a long-press or double tap selects words; the
            // router never sees those gestures.
            if (!boxHandles.handleTouch(event)) selectionGestures.onTouch(event)
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // HOT PATH: per hover event; children still see hover and scroll.
        if (event.isFromSource(InputDevice.SOURCE_CLASS_POINTER) && event.isHover()) hoverEventCount++
        if (event.actionMasked == MotionEvent.ACTION_HOVER_ENTER || event.actionMasked == MotionEvent.ACTION_HOVER_MOVE) {
            yieldTextToStylus(event)
        }
        val routed = router.onGenericMotionEvent(event)
        return super.dispatchGenericMotionEvent(event) || routed
    }

    // The pen writes over a box being edited: it drops to Preview first, on hover or at the latest at the stylus down
    // (A-045, 10-editor-ui.md#text-boxes). The pen on the grip or a knob moves the box, so the box drops to Preview there
    // too (D-041); over the toolbar or the selection menu it taps; with the Text tool it places the caret.
    private fun yieldTextToStylus(event: MotionEvent) {
        // HOT PATH: per hover event; no allocation.
        val tool = event.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_STYLUS && tool != MotionEvent.TOOL_TYPE_ERASER) return
        val active = controller.activeTool
        val x = event.x
        val y = event.y
        if (boxHandles.toolbarCovers(x, y) || selectionView.menuCovers(x, y)) return
        if (boxHandles.covers(x, y)) {
            if (boxHandles.editing) controller.textController?.blur()
        } else if (active != CanvasTool.TEXT || tool == MotionEvent.TOOL_TYPE_ERASER) {
            textField.yieldToStylus(x, y)
        }
    }

    /** While the ring is the hover cursor, the system stylus pointer (HyperOS draws a dot) is hidden (D-017). */
    override fun onResolvePointerIcon(
        event: MotionEvent,
        pointerIndex: Int,
    ): PointerIcon? {
        // HOT PATH: per hover event; the icon is created once.
        val tool = event.getToolType(pointerIndex)
        val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER
        if (stylus && hoverCursor.isEnabled) return noPointerIcon
        return super.onResolvePointerIcon(event, pointerIndex)
    }

    private fun MotionEvent.isHover(): Boolean =
        when (actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> true
            else -> false
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
        textField.refresh()
    }

    // A finger tap on a box selects it in Preview (in the box being edited it places the caret); a tap beside every box
    // ends the selection, with the Text tool it starts a new box (10-editor-ui.md#text-boxes). Read mode only reads.
    private fun onFingerTap(
        xPx: Float,
        yPx: Float,
    ) {
        val texts = controller.textController ?: return
        val pageId = document?.pages?.getOrNull(viewport.pageIndexAt(yPx))?.id ?: return
        val pt = viewport.toPagePt(pageId, PointPx(xPx, yPx))
        // Checkboxes and links act in every tool; selecting a box needs a tool other than navigate.
        if (texts.tapAt(pageId, pt.x, pt.y) || controller.activeTool == CanvasTool.NAVIGATE) return
        textField.tapped(System.nanoTime())
        texts.tapBox(pageId, pt.x, pt.y, boxSlopPt(), textTool = controller.activeTool == CanvasTool.TEXT)
    }

    // The slop around a box's lines that still takes a tap (A-059), in page pt at the current zoom.
    private fun boxSlopPt(): Float = BOX_SLOP_DP * resources.displayMetrics.density / viewport.scale

    // A pen tap with the Text tool: the box there is selected (the caret moves in the box being edited), else a new box
    // starts at the tap (10-editor-ui.md#text-boxes).
    private fun onTextToolTap(
        xPx: Float,
        yPx: Float,
    ) {
        val texts = controller.textController ?: return
        val pageId = document?.pages?.getOrNull(viewport.pageIndexAt(yPx))?.id ?: return
        val pt = viewport.toPagePt(pageId, PointPx(xPx, yPx))
        if (texts.tapAt(pageId, pt.x, pt.y)) return
        textField.tapped(System.nanoTime())
        texts.tapBox(pageId, pt.x, pt.y, boxSlopPt(), textTool = true)
    }

    private fun onViewportChanged() {
        // HOT PATH: per gesture event and animation frame.
        invalidateLayers()
        textField.place()
        if (selectionView.shown != null) selectionView.invalidate()
        if (boxHandles.shown != null) boxHandles.invalidate()
        if (boxOutlines.shown.isNotEmpty()) boxOutlines.invalidate()
        if (shapeInput.selectionPage >= 0 || shapeInput.draftPage >= 0 || snap.showing) shapePreview.invalidate()
        if (lasso.showing) lassoOverlay.invalidate()
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
        if (idle) {
            settleStartNs = if (backgroundTiles.isSettled && contentTiles.isSettled) 0L else PerfMonitor.clock.monotonicNs()
            controller.viewportSettled()
        }
        if (contentTiles.pendingCount == 0) contentTiles.warmUp(visible)
        handoff.check()
        textField.check()
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
        // A tile rendered for a page that changed meanwhile (a fast edit, text laid out a moment later) is stale.
        val outdated = backgroundTiles.takeOutdated() or contentTiles.takeOutdated()
        if (outdated) refreshTiles()
        invalidateLayers()
        val start = settleStartNs
        if (start != 0L && backgroundTiles.isSettled && contentTiles.isSettled) {
            PerfMonitor.record(SECTION_SETTLE, PerfMonitor.clock.monotonicNs() - start)
            settleStartNs = 0L
        }
        if (contentTiles.pendingCount == 0) contentTiles.warmUp(lastVisible)
        handoff.check()
        textField.check()
        boxHandles.check()
        checkPendingShape()
        checkPendingSnap()
        lasso.check()
    }

    // Like a committed shape, a snapped pen stroke leaves the preview once the tiles draw it.
    private fun checkPendingSnap() {
        val pending = snap.pending ?: return
        if (isDrawn(pending.pageId, pending.obj)) snap.committed()
    }

    /** Commits a snapped pen stroke (a recognized shape or a straight highlighter line) as one undo step. */
    private fun commitSnap(done: PendingSnap) {
        scope.launch {
            val accepted =
                try {
                    withContext(NonCancellable) { controller.execute(AddObjects(done.pageId, listOf(done.obj))) }
                } catch (e: IllegalStateException) {
                    FolioLog.w(TAG, "snap commit failed: ${e.message}", e) // session closed under the view
                    false
                }
            if (!accepted) snap.committed() else checkPendingSnap()
        }
    }

    // The shape preview hands a committed shape over to the tiles once they draw it (no blink in between).
    private fun checkPendingShape() {
        val pending = shapeInput.pending ?: return
        if (!isDrawn(pending.pageId, pending.shape)) return
        shapeInput.committed()
        if (hiddenShape?.keep == pending.shape) hideShape(null)
    }

    private fun commitShape(
        page: PageId,
        shape: Shape,
        added: Boolean,
    ) {
        val command = if (added) AddObjects(page, listOf(shape)) else UpdateObjects(page, listOf(shape))
        scope.launch {
            val accepted =
                try {
                    withContext(NonCancellable) { controller.execute(command) }
                } catch (e: IllegalStateException) {
                    FolioLog.w(TAG, "shape commit failed: ${e.message}", e) // session closed under the view
                    false
                }
            if (!accepted) shapeInput.reset() else checkPendingShape()
        }
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
                    // A detach (leaving the editor) must not drop strokes the pen already finished.
                    withContext(NonCancellable) { controller.commitStrokes(page, strokes) }
                } catch (e: IllegalStateException) {
                    FolioLog.w(TAG, "commit failed: ${e.message}", e) // session closed under the view
                    false
                }
            if (!accepted) onRejected()
        }
    }

    /**
     * True when [stroke] (any object) is in the shown document and every content tile under its on-screen
     * part is fresh (or none of it is on screen), so the next content-layer frame draws it.
     */
    private fun isDrawn(
        page: PageId,
        stroke: PageObject,
    ): Boolean {
        val doc = document ?: return false
        val stack = viewport.layout ?: return false
        val body = doc.pageBodies[page] ?: return doc.pageRef(page) == null
        // An edited shape keeps its id: the shown body must hold this version.
        if (body.objects.none { it.id == stroke.id && (stroke !is Shape || it == stroke) }) return false
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
        TileLayer(
            target,
            budgetPerLayer,
            pool,
            scope,
            controller.renderDispatcher,
            { PageRenderer(images = controller.images) },
            ::onTileReady,
        )

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

        /** From the idle tile request until every visible tile of both layers is rendered. */
        const val SECTION_SETTLE = "render:settle"

        /** Viewport idle time before tiles of the new bucket are requested. */
        const val IDLE_MS = 100L

        /** Minimum time between tile requests while panning at the requested bucket. */
        const val PAN_REQUEST_MS = 50L
    }
}

private const val MM_PER_INCH = 25.4f
private const val MIN_HOVER_RING_DP = 3f

// Like a finger tap (GestureConfig).
private const val TEXT_TAP_TIMEOUT_MS = 400L
private const val TAG = "CanvasHost"
private const val CONTENT_DESCRIPTION = "Note pages. Draw with the pen, pan and zoom with fingers."

// Base tiles render 4 half-octaves (1/4 of the px per pt) below the fit-width bucket.
private const val BASE_BUCKET_STEPS = 4

/** Share of `largeMemoryClass` for both tile caches (05-canvas-rendering.md#tiles). */
private const val BUDGET_SHARE = 4
private const val BYTES_PER_MB = 1024L * 1024L
private const val DEFAULT_LARGE_MEMORY_MB = 512

// Keyboard zoom and page jumps: one step zooms by 25%, animated over 150 ms.
private const val KEY_ZOOM_STEP = 1.25f
private const val KEY_ANIMATION_MS = 150L
private const val PAGE_JUMP_MS = 200L

// Room kept between the caret line and the view top or the chrome covering the canvas bottom.
private const val CARET_MARGIN_DP = 24f

// Slop around a text box's used lines that still takes a tap (10-editor-ui.md#text-boxes).
private const val BOX_SLOP_DP = 8f

// A polygon tap this near its first vertex (or a double tap on its last) closes it (06-ink-input.md#shapes).
private const val POLYGON_CLOSE_DP = 12f

// Strokes smaller than this (bounds diagonal) never snap to a shape or straight line on a hold.
private const val MIN_SNAP_DP = 12f

// Grab radius of a selected shape's handles (48 dp targets) and the rotate handle's distance above the box.
private const val SHAPE_HANDLE_DP = 24f
private const val SHAPE_ROTATE_OFFSET_DP = 28f

// A lasso scale keeps every side it changes at least this long on screen (A-066).
private const val LASSO_MIN_SIDE_DP = 8f

// The moving lasso selection: drawn with this margin around its box, at most this many px on its longer side.
private const val SNAPSHOT_MARGIN_PT = 2f
private const val MAX_SNAPSHOT_PX = 4096f
private const val SECTION_LASSO_SNAPSHOT = "lasso:snapshot"

// The text color of a moved box on a page without a known paper (near-black, like the live field's default).
private const val BOX_TEXT_ARGB = 0xFF1B1D22.toInt()

private fun tileBudgetBytes(context: Context): Long {
    val reported = context.getSystemService(ActivityManager::class.java)?.largeMemoryClass ?: 0
    val mb = if (reported > 0) reported else DEFAULT_LARGE_MEMORY_MB
    return mb * BYTES_PER_MB / BUDGET_SHARE
}

/** The activity behind this context (the editor window), or null. */
internal tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
