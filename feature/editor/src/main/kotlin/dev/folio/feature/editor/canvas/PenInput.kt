package dev.folio.feature.editor.canvas

import android.graphics.Matrix
import android.view.MotionEvent
import androidx.annotation.MainThread
import dev.folio.core.ink.input.StylusCapabilities
import dev.folio.core.ink.input.StylusFeatures
import dev.folio.core.ink.input.StylusTarget
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
) : StylusTarget {
    private val strokeToView = Matrix()
    private var inking = false
    private var snapping = false
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
        val scale = viewport.scale
        val originX = viewport.pageOriginViewX(page).toFloat()
        val originY = viewport.pageOriginViewY(page).toFloat()
        val spec = brush()
        pageIndex = page
        snap?.begin(pageId, page, spec, originX, originY, scale, straightOnly())
        snap?.track(event, index)
        if (snap?.snapped == true) {
            snapping = true
            return
        }
        strokeToView.setScale(scale, scale)
        strokeToView.postTranslate(originX, originY)
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
        surface.finish(event, pointerId)
        inking = false
        finished++
        snap?.stop()
    }

    override fun onStylusCancel(
        event: MotionEvent,
        pointerId: Int,
    ) {
        snap?.stop()
        if (snapping) {
            snapping = false
            return
        }
        if (!inking) return
        surface.cancel(event)
        inking = false
        canceled++
    }
}
