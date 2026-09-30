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
)

/**
 * The pen tool on the stylus route (06-ink-input.md#wet-ink): a stylus down starts a wet stroke on the
 * page under the pen (the nearest page from a gap) in that page's points, so the finished stroke is
 * already in page space; the surface reports it finished with that page for the dry handoff.
 * Eraser-tool pointers are ignored ([StylusTools] routes them to [EraserInput]).
 * [onStrokeStart] runs first on every stroke (the host requests unbuffered dispatch).
 */
@MainThread
internal class PenInput(
    private val viewport: Viewport,
    private val surface: WetSurface,
    private val brush: () -> BrushSpec,
    private val capabilities: () -> StylusCapabilities,
    private val onStrokeStart: (MotionEvent) -> Unit,
) : StylusTarget {
    private val strokeToView = Matrix()
    private var inking = false
    private var started = 0
    private var finished = 0
    private var canceled = 0

    /** Page (stack index) of the current or last stroke; -1 before the first. */
    var pageIndex = -1
        private set

    /** Current counters. */
    val stats: InkStats get() = InkStats(started, finished, canceled)

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
        strokeToView.setScale(scale, scale)
        strokeToView.postTranslate(viewport.pageOriginViewX(page).toFloat(), viewport.pageOriginViewY(page).toFloat())
        surface.start(event, pointerId, brush(), StylusFeatures.tiltShading(capabilities()), strokeToView, pageId)
        pageIndex = page
        inking = true
        started++
    }

    override fun onStylusMove(
        event: MotionEvent,
        pointerId: Int,
    ) {
        // HOT PATH: per MotionEvent.
        if (inking) surface.add(event, pointerId)
    }

    override fun onStylusUp(
        event: MotionEvent,
        pointerId: Int,
    ) {
        if (!inking) return
        surface.finish(event, pointerId)
        inking = false
        finished++
    }

    override fun onStylusCancel(
        event: MotionEvent,
        pointerId: Int,
    ) {
        if (!inking) return
        surface.cancel(event)
        inking = false
        canceled++
    }
}
