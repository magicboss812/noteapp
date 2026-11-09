package dev.folio.feature.editor.canvas

import android.graphics.Matrix
import android.view.MotionEvent
import androidx.annotation.MainThread
import dev.folio.core.ink.input.StylusCapabilities
import dev.folio.core.ink.input.StylusFeatures
import dev.folio.core.ink.input.StylusTarget
import dev.folio.core.ink.ruler.Ruler
import dev.folio.core.model.BrushSpec
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY

/** Wet-ink counters for debug `state`, since the canvas view was created. */
data class InkStats(
    /** Strokes started. */
    val started: Int,
    /** Strokes finished (pen lifted). */
    val finished: Int,
    /** Strokes canceled (ACTION_CANCEL, FLAG_CANCELED). */
    val canceled: Int,
    /** Wet strokes a hold turned into a shape or straight line (06-ink-input.md#shape-recognition). */
    val snapped: Int = 0,
)

/**
 * The pen tool on the stylus route (06-ink-input.md#wet-ink): a stylus down starts a wet stroke on the
 * page under the pen (the nearest page from a gap) in that page's points, so the finished stroke is
 * already in page space; the surface reports it finished with that page for the dry handoff.
 * Eraser-tool pointers are ignored ([StylusTools] routes them to [EraserInput]).
 * [onStrokeStart] runs first on every stroke (the host requests unbuffered dispatch).
 * With [snap] a hold turns the stroke into a shape or straight line: the wet stroke is canceled and the rest of the
 * gesture goes to the snap; while [straightOnly] a highlighter draws no wet stroke at all (D-024, A-065).
 * A stroke starting within [rulerSnapPx] of an edge of the shown [ruler] follows that edge: every sample is projected
 * onto the edge line and fed as inputs, no hold snapping (06-ink-input.md#ruler).
 */
@MainThread
internal class PenInput(
    private val viewport: Viewport,
    private val surface: WetSurface,
    private val brush: () -> BrushSpec,
    private val capabilities: () -> StylusCapabilities,
    private val onStrokeStart: (MotionEvent) -> Unit,
    private val snap: PenSnap? = null,
    private val straightOnly: () -> Boolean = { false },
    private val ruler: () -> Ruler? = { null },
    private val rulerSnapPx: Float = 0f,
) : StylusTarget {
    private val strokeToView = Matrix()
    private var inking = false
    private var snapping = false

    // The ruler edge the current stroke follows (Ruler.NO_EDGE: a free stroke) and the page origin and scale it maps to.
    private var edge = Ruler.NO_EDGE
    private var followed: Ruler? = null
    private var originX = 0f
    private var originY = 0f
    private var scale = 1f
    private var started = 0
    private var finished = 0
    private var canceled = 0
    private var snapped = 0

    init {
        snap?.onSnapped = ::dropWetStroke
    }

    /** Page (stack index) of the current or last stroke; -1 before the first. */
    var pageIndex = -1
        private set

    /** Current counters. */
    val stats: InkStats get() = InkStats(started, finished, canceled, snapped)

    // A hold snapped the stroke: the wet stroke goes, the snap's preview takes over until the pen lifts.
    private fun dropWetStroke() {
        if (!inking) return
        surface.cancel(null)
        inking = false
        snapping = true
        snapped++
    }

    override fun onStylusDown(
        event: MotionEvent,
        pointerId: Int,
        eraser: Boolean,
    ) {
        // HOT PATH: per stroke; the matrix is reused.
        if (eraser) return
        val index = event.findPointerIndex(pointerId)
        val page = if (index < 0) -1 else viewport.pageIndexAt(event.getY(index))
        val pageId =
            viewport.layout
                ?.pages
                ?.getOrNull(page)
                ?.id ?: return
        onStrokeStart(event)
        scale = viewport.scale
        originX = viewport.pageOriginViewX(page).toFloat()
        originY = viewport.pageOriginViewY(page).toFloat()
        val spec = brush()
        pageIndex = page
        strokeToView.setScale(scale, scale)
        strokeToView.postTranslate(originX, originY)
        val shown = ruler()
        edge = shown?.edgeNear(event.getX(index), event.getY(index), rulerSnapPx) ?: Ruler.NO_EDGE
        if (shown != null && edge != Ruler.NO_EDGE) {
            followed = shown
            val x = event.getX(index)
            val y = event.getY(index)
            surface.startInput(ptX(shown, x, y), ptY(shown, x, y), pressureOf(event, index), spec, strokeToView, pageId)
            inking = true
            started++
            return
        }
        snap?.begin(pageId, page, spec, originX, originY, scale, straightOnly())
        snap?.track(event, index)
        if (snap?.snapped == true) {
            snapping = true
            return
        }
        surface.start(event, pointerId, spec, StylusFeatures.tiltShading(capabilities()), strokeToView, pageId)
        inking = true
        started++
    }

    override fun onStylusMove(
        event: MotionEvent,
        pointerId: Int,
    ) {
        // HOT PATH: per MotionEvent.
        if (!inking && !snapping) return
        if (edge != Ruler.NO_EDGE) {
            feedProjected(event, pointerId)
            return
        }
        if (inking) surface.add(event, pointerId)
        val index = event.findPointerIndex(pointerId)
        if (index >= 0) snap?.track(event, index)
    }

    override fun onStylusUp(
        event: MotionEvent,
        pointerId: Int,
    ) {
        if (snapping) {
            onStylusMove(event, pointerId)
            snapping = false
            snap?.finish()
            return
        }
        if (!inking) return
        val shown = followed
        val index = event.findPointerIndex(pointerId)
        if (edge != Ruler.NO_EDGE && shown != null && index >= 0) {
            // The stroke ends where the pen lifts, on the edge.
            feedProjected(event, pointerId, withCurrent = false)
            val x = event.getX(index)
            val y = event.getY(index)
            surface.finishInput(ptX(shown, x, y), ptY(shown, x, y), event.eventTime - event.downTime, pressureOf(event, index))
            endRulerStroke()
        } else {
            surface.finish(event, pointerId)
        }
        inking = false
        finished++
        snap?.stop()
    }

    // Projects [event]'s samples of [pointerId] (historical first, then the current one unless not [withCurrent]) onto
    // the followed edge and hands them to the stroke.
    private fun feedProjected(
        event: MotionEvent,
        pointerId: Int,
        withCurrent: Boolean = true,
    ) {
        // HOT PATH: per MotionEvent; no allocation.
        val shown = followed ?: return
        val index = event.findPointerIndex(pointerId)
        if (index < 0 || !inking) return
        val t0 = event.downTime
        for (h in 0 until event.historySize) {
            val x = event.getHistoricalX(index, h)
            val y = event.getHistoricalY(index, h)
            val pressure = event.getHistoricalPressure(index, h).coerceIn(0f, 1f)
            surface.addInput(ptX(shown, x, y), ptY(shown, x, y), event.getHistoricalEventTime(h) - t0, pressure)
        }
        if (withCurrent) {
            val x = event.getX(index)
            val y = event.getY(index)
            surface.addInput(ptX(shown, x, y), ptY(shown, x, y), event.eventTime - t0, pressureOf(event, index))
        }
        surface.flushInputs()
    }

    private fun endRulerStroke() {
        edge = Ruler.NO_EDGE
        followed = null
    }

    // The projection of view point ([x], [y]) onto the followed edge, in the stroke's page pt.
    private fun ptX(
        shown: Ruler,
        x: Float,
        y: Float,
    ): Float = (shown.projectX(x, y, edge) - originX) / scale

    private fun ptY(
        shown: Ruler,
        x: Float,
        y: Float,
    ): Float = (shown.projectY(x, y, edge) - originY) / scale

    private fun pressureOf(
        event: MotionEvent,
        index: Int,
    ): Float = event.getPressure(index).coerceIn(0f, 1f)

    override fun onStylusCancel(
        event: MotionEvent,
        pointerId: Int,
    ) {
        snap?.stop()
        if (snapping) {
            snapping = false
            return
        }
        endRulerStroke()
        if (!inking) return
        surface.cancel(event)
        inking = false
        canceled++
    }
}
