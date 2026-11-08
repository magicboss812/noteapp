package dev.folio.feature.editor.canvas

import android.view.Choreographer
import androidx.annotation.MainThread
import dev.folio.core.model.Document
import dev.folio.core.model.GridUnit
import dev.folio.core.model.PageId
import dev.folio.core.model.TextZones
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageOriginViewY

/**
 * Moves the selected box in Preview while arrow keys are held (10-editor-ui.md#text-boxes, Move; P07-T14): once per
 * display frame [BoxKeyMotion] gives the distance since the last frame, which goes to [handles] as the new spot of the
 * box (x free, Up/Down by rules through [TextZones.lineStepTop], Shift+Up/Down between them, all inside the page text
 * zone); [follow] keeps the box in view. The last key up places the box as one undo step. No allocation per frame,
 * no recomposition.
 */
@MainThread
internal class BoxKeyMover(
    private val viewport: Viewport,
    private val density: Float,
    private val handles: BoxHandles,
    private val document: () -> Document?,
    private val follow: () -> Unit,
) {
    private val motion = BoxKeyMotion()
    private val choreographer = Choreographer.getInstance()
    private val frame = Choreographer.FrameCallback(::onFrame)
    private var posted = false

    // The hold's page and grid, and the limits of the box's text column top-left there (from TextZones.keyedBox).
    private var page: PageId? = null
    private var pageIndex = -1
    private var grid: GridUnit? = null
    private var minLeftPt = 0f
    private var maxLeftPt = 0f
    private var minTopPt = 0f
    private var maxTopPt = 0f
    private var heightPt = 0f
    private var leftPt = 0f
    private var topPt = 0f

    /** True while keys move the box. */
    val active: Boolean get() = motion.active && handles.keysHeld

    /** A key event from the screen; key downs only arrive while a box is in Preview. */
    fun onKey(event: BoxKeyEvent) {
        if (event.down) press(event.key, event.smooth) else release(event.key)
    }

    /** The moving box's first text column in view px into [out] (top, bottom); false while no key move runs. */
    fun lineOf(out: FloatArray): Boolean {
        if (page == null || !active || pageIndex < 0) return false
        val y = viewport.pageOriginViewY(pageIndex).toFloat()
        out[0] = y + topPt * viewport.scale
        out[1] = y + (topPt + heightPt) * viewport.scale
        return true
    }

    /** Ends a running move at once, placing the box where it is (the view goes away). */
    fun stop() {
        if (!motion.active) return
        motion.reset()
        cancelFrame()
        handles.endKeys()
        page = null
    }

    private fun press(
        key: BoxKey,
        smooth: Boolean,
    ) {
        if (!motion.active && !begin()) return
        if (!motion.press(key, smooth, System.nanoTime())) return
        step(System.nanoTime())
        if (!posted) {
            posted = true
            choreographer.postFrameCallback(frame)
        }
    }

    private fun release(key: BoxKey) {
        if (!motion.active) return
        motion.release(key)
        if (motion.active) return
        cancelFrame()
        motion.reset()
        handles.endKeys()
        page = null
    }

    // A new hold: where the box is and how far it may go on its page.
    private fun begin(): Boolean {
        val (on, rect) = handles.beginKeys() ?: return false
        val doc = document()
        val ref = doc?.pageRef(on)
        if (ref == null) {
            handles.endKeys()
            return false
        }
        pageIndex = doc.pages.indexOf(ref)
        val template = ref.background.template
        val low = TextZones.keyedBox(ref.spec, template, rect, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)
        val high = TextZones.keyedBox(ref.spec, template, rect, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)
        page = on
        grid = GridUnit.of(template)
        minLeftPt = low.left
        maxLeftPt = high.left
        minTopPt = low.top
        maxTopPt = high.top
        heightPt = rect.heightPt
        leftPt = rect.left.coerceIn(minLeftPt, maxLeftPt)
        topPt = rect.top
        return true
    }

    private fun onFrame(frameTimeNanos: Long) {
        // HOT PATH: once per display frame while an arrow key is held.
        posted = false
        if (!motion.active) return
        if (!handles.keysHeld) {
            // The box went (deleted, deselected): the keys move nothing any more.
            motion.reset()
            page = null
            return
        }
        step(frameTimeNanos)
        posted = true
        choreographer.postFrameCallback(frame)
    }

    // Applies the motion up to [nowNs]: x and smooth y in pt, line steps from rule to rule, clamped to the zone.
    private fun step(nowNs: Long) {
        motion.advance(nowNs, density / viewport.scale)
        val g = grid ?: return
        leftPt = (leftPt + motion.dxPt).coerceIn(minLeftPt, maxLeftPt)
        topPt = motion.topAfter(g, topPt).coerceIn(minTopPt, maxTopPt)
        handles.keyMove(leftPt, topPt)
        follow()
    }

    private fun cancelFrame() {
        if (!posted) return
        choreographer.removeFrameCallback(frame)
        posted = false
    }
}
