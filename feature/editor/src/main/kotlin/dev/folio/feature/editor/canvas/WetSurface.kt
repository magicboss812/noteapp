package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.folio.core.common.PerfMonitor
import dev.folio.core.ink.StrokeBuilder
import dev.folio.core.ink.brush.BrushCatalog
import dev.folio.core.ink.brush.BrushTextures
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.PageId
import dev.folio.core.model.StrokeInputs

/** A finished wet stroke on [page] in page points; [key] identifies it for [WetSurface.remove]. */
internal class WetStroke(
    val key: Any,
    val page: PageId,
    val spec: BrushSpec,
    val inputs: StrokeInputs,
)

/**
 * Where wet strokes draw (06-ink-input.md#wet-ink): androidx.ink's [InProgressStrokesView] on the
 * device ([InkWetSurface]), a recorder in tests. One stroke at a time; stroke coordinates are page
 * points and [start]'s `strokeToView` maps them to view px. Finished strokes stay visible until the
 * dry handoff [remove]s them (05-canvas-rendering.md#dry-handoff).
 */
@MainThread
internal interface WetSurface {
    /** The wet layer, stacked on top of the canvas. */
    val view: View

    /** Receives finished strokes (main thread), possibly several per call. */
    var onFinished: (List<WetStroke>) -> Unit

    /** Starts a stroke on [page] for pointer [pointerId] of [event] with [spec] (tilt behaviors when [tilt]). */
    @Suppress("LongParameterList") // the androidx.ink startStroke arguments plus the page
    fun start(
        event: MotionEvent,
        pointerId: Int,
        spec: BrushSpec,
        tilt: Boolean,
        strokeToView: Matrix,
        page: PageId,
    )

    /** Adds [event]'s samples (historical included) of [pointerId] to the stroke. */
    fun add(
        event: MotionEvent,
        pointerId: Int,
    )

    /** Ends the stroke with [event]; it stays visible and arrives at [onFinished]. */
    fun finish(
        event: MotionEvent,
        pointerId: Int,
    )

    /**
     * Starts a stroke from inputs the caller computes (the ruler projects the pen onto its edge, 06-ink-input.md#ruler):
     * the first input at ([xPt], [yPt]) on [page] with [pressure] (0..1). Continue with [addInput] + [flushInputs], end
     * with [finishInput] or [cancel].
     */
    @Suppress("LongParameterList") // the androidx.ink startStroke arguments plus the page
    fun startInput(
        xPt: Float,
        yPt: Float,
        pressure: Float,
        spec: BrushSpec,
        strokeToView: Matrix,
        page: PageId,
    )

    /** Queues an input [elapsedMs] after the first one; inputs earlier than the last are dropped. */
    fun addInput(
        xPt: Float,
        yPt: Float,
        elapsedMs: Long,
        pressure: Float,
    )

    /** Hands the queued inputs to the stroke (once per MotionEvent). */
    fun flushInputs()

    /** Ends a [startInput] stroke with its last input; it stays visible and arrives at [onFinished]. */
    fun finishInput(
        xPt: Float,
        yPt: Float,
        elapsedMs: Long,
        pressure: Float,
    )

    /** Discards the stroke; [event] is the cancel event, null when a hold snapped it (no event at hand). */
    fun cancel(event: MotionEvent?)

    /** Stops showing the finished strokes with [keys] (their committed copies are on screen). */
    fun remove(keys: Collection<Any>)
}

/** [WetSurface] on androidx.ink: front-buffered wet strokes with catalog brushes and their textures. */
internal class InkWetSurface(
    context: Context,
    private val catalog: BrushCatalog = BrushCatalog.DEFAULT,
) : WetSurface,
    InProgressStrokesFinishedListener {
    private class Started(
        val page: PageId,
        val spec: BrushSpec,
    )

    private val ink =
        InProgressStrokesView(context).apply {
            textureBitmapStore = BrushTextures
            addFinishedStrokesListener(this@InkWetSurface)
            // Lazy init on the first stroke sometimes never created the front-buffer SurfaceView after a
            // cold start (strokes then never finish, P03-T07); initialize once attached and laid out.
            addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) {
                        v.post { if (v.isAttachedToWindow) eagerInit() }
                    }

                    override fun onViewDetachedFromWindow(v: View) = Unit
                },
            )
        }
    private val identity = Matrix()
    private val started = HashMap<InProgressStrokeId, Started>()
    private var strokeId: InProgressStrokeId? = null
    private var brushSpec: BrushSpec? = null
    private var brushTilt = false
    private var brush: Brush? = null

    // Ruler strokes: the projected inputs of one MotionEvent, and the first or last input.
    private val batch = MutableStrokeInputBatch()
    private val input = StrokeInput()
    private var lastInputMs = 0L

    override val view: View get() = ink

    override var onFinished: (List<WetStroke>) -> Unit = {}

    override fun start(
        event: MotionEvent,
        pointerId: Int,
        spec: BrushSpec,
        tilt: Boolean,
        strokeToView: Matrix,
        page: PageId,
    ) {
        // HOT PATH: per stroke; the brush is rebuilt only when the spec changes.
        strokeId?.let { cancelStroke(it, event) }
        val id = ink.startStroke(event, pointerId, brushFor(spec, tilt), identity, strokeToView)
        started[id] = Started(page, spec)
        strokeId = id
    }

    override fun add(
        event: MotionEvent,
        pointerId: Int,
    ) {
        // HOT PATH: per MotionEvent.
        val id = strokeId ?: return
        ink.addToStroke(event, pointerId, id)
    }

    override fun finish(
        event: MotionEvent,
        pointerId: Int,
    ) {
        val id = strokeId ?: return
        ink.finishStroke(event, pointerId, id)
        strokeId = null
    }

    override fun startInput(
        xPt: Float,
        yPt: Float,
        pressure: Float,
        spec: BrushSpec,
        strokeToView: Matrix,
        page: PageId,
    ) {
        // HOT PATH: per stroke; the input and batch are reused.
        strokeId?.let { cancelStroke(it, null) }
        batch.clear()
        lastInputMs = 0L
        input.update(xPt, yPt, 0L, InputToolType.STYLUS, pressure = pressure)
        val id = ink.startStroke(input, brushFor(spec, tilt = false), strokeToView)
        started[id] = Started(page, spec)
        strokeId = id
    }

    override fun addInput(
        xPt: Float,
        yPt: Float,
        elapsedMs: Long,
        pressure: Float,
    ) {
        // HOT PATH: per pen sample; no allocation.
        if (strokeId == null || elapsedMs < lastInputMs) return
        lastInputMs = elapsedMs
        batch.add(InputToolType.STYLUS, xPt, yPt, elapsedMs, pressure = pressure)
    }

    override fun flushInputs() {
        // HOT PATH: per MotionEvent; androidx.ink copies the batch.
        val id = strokeId ?: return
        if (batch.size == 0) return
        ink.addToStroke(batch, id)
        batch.clear()
    }

    override fun finishInput(
        xPt: Float,
        yPt: Float,
        elapsedMs: Long,
        pressure: Float,
    ) {
        val id = strokeId ?: return
        flushInputs()
        input.update(xPt, yPt, maxOf(elapsedMs, lastInputMs), InputToolType.STYLUS, pressure = pressure)
        ink.finishStroke(input, id)
        strokeId = null
    }

    override fun cancel(event: MotionEvent?) {
        batch.clear()
        val id = strokeId ?: return
        cancelStroke(id, event)
        strokeId = null
    }

    override fun remove(keys: Collection<Any>) {
        val ids = keys.filterIsInstanceTo(HashSet<InProgressStrokeId>())
        if (ids.isNotEmpty()) ink.removeFinishedStrokes(ids)
    }

    override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) =
        PerfMonitor.trace(DryHandoff.SECTION_COMMIT) {
            val finished = ArrayList<WetStroke>(strokes.size)
            for ((id, stroke) in strokes) {
                val info = started.remove(id)
                if (info == null) {
                    ink.removeFinishedStrokes(setOf(id)) // not ours to commit
                    continue
                }
                finished += WetStroke(id, info.page, info.spec, StrokeBuilder.inputsOf(stroke.inputs))
            }
            if (finished.isNotEmpty()) onFinished(finished)
        }

    private fun cancelStroke(
        id: InProgressStrokeId,
        event: MotionEvent?,
    ) {
        ink.cancelStroke(id, event)
        started.remove(id)
    }

    private fun brushFor(
        spec: BrushSpec,
        tilt: Boolean,
    ): Brush {
        val cached = brush
        if (cached != null && spec == brushSpec && tilt == brushTilt) return cached
        return catalog.brush(spec, tilt).also {
            brush = it
            brushSpec = spec
            brushTilt = tilt
        }
    }
}
