package dev.folio.feature.editor.canvas

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.annotation.MainThread
import androidx.ink.authoring.InProgressStrokesView
import dev.folio.core.common.PerfMonitor
import dev.folio.core.model.PageRef
import dev.folio.core.render.DisplayModeHelper
import dev.folio.core.render.viewport.Viewport

/**
 * The editor canvas (05-canvas-rendering.md#layers), hosted by an `AndroidView`. Children bottom to
 * top: [BackgroundTileLayer], [ContentTileLayer], an overlay slot for Compose, and androidx.ink's
 * [InProgressStrokesView]. All touch input arrives in [dispatchTouchEvent]; today only finger pan and
 * zoom ([FingerGestures]), stylus routing arrives with InputRouter (P03-T06). While attached it
 * requests the fastest display mode ([DisplayModeHelper]).
 */
@SuppressLint("ViewConstructor") // created in code by CanvasHost only
@MainThread
class CanvasHostView internal constructor(
    context: Context,
    private val controller: CanvasController,
    createWetLayer: (Context) -> View,
) : FrameLayout(context) {
    /** Host for [controller] with the androidx.ink wet-ink layer. */
    constructor(context: Context, controller: CanvasController) : this(context, controller, ::InProgressStrokesView)

    private val viewport: Viewport get() = controller.viewport
    private val background = BackgroundTileLayer(context, controller.viewport)
    private val content = ContentTileLayer(context)
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
                ),
                onChanged = ::onViewportChanged,
                postFrame = ::postOnAnimation,
            )
        }
    private val animator = ViewportAnimator(controller.viewport, ::onViewportChanged)
    private var pages: List<PageRef>? = null

    /** Slot for the Compose overlay (focused text, selection, lasso, ...), above committed content. */
    val overlay = FrameLayout(context)

    /** Wet ink layer, always on top. */
    val wetLayer: View = createWetLayer(context)

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

    /** Lays out [next] (the document's page list); keeps the viewport position when pages change. */
    fun setPages(next: List<PageRef>) {
        if (next == pages) return
        pages = next
        layoutPages()
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
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        gestures.release()
        displayModes?.release()
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
    override fun dispatchTouchEvent(event: MotionEvent): Boolean =
        PerfMonitor.trace(SECTION_TOUCH) {
            // HOT PATH: per MotionEvent; routing only.
            if (event.actionMasked == MotionEvent.ACTION_DOWN) animator.cancel()
            gestures.onTouchEvent(event)
            true
        }

    private fun layoutPages() {
        val current = pages ?: return
        if (width == 0 || height == 0) return
        viewport.setPages(current, width.toFloat(), height.toFloat())
        onViewportChanged()
    }

    private fun onViewportChanged() {
        background.invalidate()
        content.invalidate()
    }

    /** PerfMonitor sections. */
    companion object {
        /** Time spent routing one MotionEvent. */
        const val SECTION_TOUCH = "canvas:touch"
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
