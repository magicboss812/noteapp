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
import androidx.ink.strokes.Stroke
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

    /** Discards the stroke. */
    fun cancel(event: MotionEvent)

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

    override fun cancel(event: MotionEvent) {
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
        event: MotionEvent,
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
