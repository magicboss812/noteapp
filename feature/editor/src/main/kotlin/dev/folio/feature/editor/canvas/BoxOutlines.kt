package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.View
import androidx.annotation.MainThread
import dev.folio.core.model.Document
import dev.folio.core.model.PageId
import dev.folio.core.model.StickyNote
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import dev.folio.feature.editor.text.TextSnapshot

/**
 * The outline of every page slot of [snapshot]'s flows by page (FlowLayout.outlineOf), for [BoxOutlines]. Sticky note
 * slots (sticky-local coordinates) and the selected box, which shows its own outline (BoxHandles), are left out.
 */
internal fun boxOutlines(
    snapshot: TextSnapshot,
    doc: Document,
): Map<PageId, List<RectPt>> {
    val focused = snapshot.box?.flowId ?: snapshot.field?.focus?.flowId
    val out = HashMap<PageId, MutableList<RectPt>>()
    for ((flowId, layout) in snapshot.layouts) {
        if (flowId == focused) continue
        for (i in layout.slots.indices) {
            val slot = layout.slots[i]
            val sticky = doc.pageBodies[slot.pageId]?.objects?.any { it.id == slot.frameId && it is StickyNote } == true
            if (!sticky) out.getOrPut(slot.pageId) { ArrayList() } += layout.outlineOf(i)
        }
    }
    return out
}

/**
 * The faint dashed outlines of every text box on screen while the Text tool is active (10-editor-ui.md#text-boxes,
 * A-059): overlay only, 1 dp neutral gray at every zoom, never in tiles. The outlines come in page pt per page
 * ([show]); an empty map hides them.
 */
@MainThread
internal class BoxOutlines(
    context: Context,
    private val viewport: Viewport,
    private val document: () -> Document?,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = OUTLINE_ARGB
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_DP * density
            pathEffect = DashPathEffect(floatArrayOf(DASH_DP * density, GAP_DP * density), 0f)
        }

    /** The outlines shown, by page (tests read it). */
    var shown: Map<PageId, List<RectPt>> = emptyMap()
        private set

    /** Shows [outlines] (page pt, by page); empty hides them. */
    fun show(outlines: Map<PageId, List<RectPt>>) {
        if (outlines == shown) return
        shown = outlines
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // HOT PATH: per viewport change while the Text tool is active; no allocation.
        if (shown.isEmpty()) return
        val pages = document()?.pages ?: return
        val scale = viewport.scale
        for (index in pages.indices) {
            val rects = shown[pages[index].id] ?: continue
            val x = viewport.pageOriginViewX(index).toFloat()
            val y = viewport.pageOriginViewY(index).toFloat()
            for (i in rects.indices) {
                val r = rects[i]
                canvas.drawRect(x + r.left * scale, y + r.top * scale, x + r.right * scale, y + r.bottom * scale, paint)
            }
        }
    }

    companion object {
        private const val OUTLINE_DP = 1f
        private const val DASH_DP = 4f
        private const val GAP_DP = 3f

        // Neutral gray, named (no theme inside the canvas view).
        private const val OUTLINE_ARGB = 0xFFA6A6AD.toInt()
    }
}
