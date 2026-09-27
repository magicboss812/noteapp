package dev.folio.app.spikes

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.SystemClock
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import dev.folio.core.common.PerfMonitor
import dev.folio.core.render.DisplayModeHelper

/** When finished wet strokes are removed from [InProgressStrokesView] (P01-S1). */
internal enum class Handoff {
    /** In `onStrokesFinished`, right after invalidating the committed layer (androidx.ink sample). */
    IMMEDIATE,

    /** In the next Choreographer frame callback (05-canvas-rendering.md#dry-handoff step 3). */
    FRAME,

    /** After the frame that draws the committed stroke was submitted (ViewTreeObserver frame commit). */
    COMMIT,
    ;

    companion object {
        fun parse(name: String): Handoff? = entries.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }
}

/**
 * Spike P01-S1: a white page drawing committed strokes with [CanvasStrokeRenderer] under an
 * [InProgressStrokesView] fed by stylus events, with the highest refresh mode requested.
 * Kept for the P01-S1 USER-CHECKs; removed by P03-T09 (STATUS D-002).
 */
@SuppressLint("ViewConstructor") // created in code by AppDebugHooks only
@MainThread
internal class SpikeInkView(
    activity: Activity,
) : FrameLayout(activity),
    InProgressStrokesFinishedListener {
    private val committed = CommittedStrokesView(activity)
    private val wet = InProgressStrokesView(activity)
    private val displayModes = DisplayModeHelper(activity.window)
    private val brush = Brush.createWithColorIntArgb(StockBrushes.pressurePen(), INK_ARGB, BRUSH_SIZE_PX, BRUSH_EPSILON)
    private var strokeId: InProgressStrokeId? = null
    private var pointerId = NO_POINTER

    var handoff = Handoff.COMMIT
    var requestedHz: Float? = null
        private set
    val committedCount: Int get() = committed.strokes.size
    var pendingWetCount = 0
        private set

    init {
        addView(committed, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(wet, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        wet.addFinishedStrokesListener(this)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestedHz = display?.let(displayModes::request)
    }

    override fun onDetachedFromWindow() {
        displayModes.release()
        super.onDetachedFromWindow()
    }

    /** Active refresh rate of the display showing this view. */
    fun activeHz(): Float? = display?.refreshRate

    fun clear() {
        committed.strokes.clear()
        committed.invalidate()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean = PerfMonitor.trace(SECTION_TOUCH) { handleTouch(event) }

    // HOT PATH: runs per MotionEvent; no allocation, no logging.
    private fun handleTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (event.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) return true // fingers never draw
                requestUnbufferedDispatch(event)
                pointerId = event.getPointerId(0)
                strokeId = wet.startStroke(event, pointerId, brush)
            }

            MotionEvent.ACTION_MOVE -> {
                strokeId?.let { wet.addToStroke(event, pointerId, it) }
            }

            MotionEvent.ACTION_UP -> {
                val id = strokeId ?: return true
                if (event.flags and MotionEvent.FLAG_CANCELED != 0) wet.cancelStroke(id, event) else wet.finishStroke(event, pointerId, id)
                strokeId = null
                pointerId = NO_POINTER
            }

            MotionEvent.ACTION_CANCEL -> {
                strokeId?.let { wet.cancelStroke(it, event) }
                strokeId = null
                pointerId = NO_POINTER
            }
        }
        return true
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) {
        val finishedNs = SystemClock.elapsedRealtimeNanos()
        committed.strokes.addAll(strokes.values)
        committed.invalidate()
        pendingWetCount += strokes.size
        val remove = {
            wet.removeFinishedStrokes(strokes.keys)
            pendingWetCount -= strokes.size
            PerfMonitor.record(SECTION_HANDOFF, SystemClock.elapsedRealtimeNanos() - finishedNs)
        }
        when (handoff) {
            Handoff.IMMEDIATE -> remove()
            Handoff.FRAME -> Choreographer.getInstance().postFrameCallback { remove() }
            Handoff.COMMIT -> committed.viewTreeObserver.registerFrameCommitCallback(remove)
        }
    }

    /** The committed-content layer: paper plus every finished stroke, redrawn in full (spike only). */
    private class CommittedStrokesView(
        context: Context,
    ) : View(context) {
        val strokes = ArrayList<Stroke>()
        private val renderer = CanvasStrokeRenderer.create()
        private val identity = Matrix()

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.WHITE)
            for (stroke in strokes) renderer.draw(canvas, stroke, identity)
        }
    }

    companion object {
        const val ROUTE = "spike-ink"
        const val SECTION_TOUCH = "ink:onTouch"
        const val SECTION_HANDOFF = "ink:handoff"
        private const val INK_ARGB = 0xFF1A1A1A.toInt()
        private const val BRUSH_SIZE_PX = 6f
        private const val BRUSH_EPSILON = 0.1f
        private const val NO_POINTER = -1
    }
}
