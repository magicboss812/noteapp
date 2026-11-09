package dev.folio.core.render

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.annotation.AnyThread
import dev.folio.core.model.StickyNote
import dev.folio.core.text.flow.PageText

/**
 * Paints sticky notes (11-design-system.md#sticky-notes): the shadow, the rounded paper and the text of its flow, all
 * in page space so export shows them too, turned around the sticky's center unless its text is edited (A-068). The
 * canvas maps page pt to px. Not thread-safe: one per [PageRenderer].
 */
internal class StickyPainter(
    private val text: TextRunPainter,
) {
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = StickyNote.SHADOW_ARGB
            maskFilter = BlurMaskFilter(StickyNote.SHADOW_BLUR_PT, BlurMaskFilter.Blur.NORMAL)
        }
    private val rect = RectF()

    /** Paints [sticky] with its runs from [pageText]; [scale] px per pt; table borders in [borderArgb]. */
    @AnyThread
    fun draw(
        canvas: Canvas,
        sticky: StickyNote,
        pageText: PageText,
        scale: Float,
        borderArgb: Int,
    ) {
        val r = sticky.rect
        val deg = if (pageText.isStraight(sticky.id)) 0f else sticky.rotationDeg
        canvas.save()
        if (deg != 0f) canvas.rotate(deg, (r.left + r.right) / 2f, (r.top + r.bottom) / 2f)
        rect.set(r.left, r.top + StickyNote.SHADOW_DY_PT, r.right, r.bottom + StickyNote.SHADOW_DY_PT)
        canvas.drawRoundRect(rect, StickyNote.RADIUS_PT, StickyNote.RADIUS_PT, shadow)
        rect.set(r.left, r.top, r.right, r.bottom)
        paper.color = sticky.argb
        canvas.drawRoundRect(rect, StickyNote.RADIUS_PT, StickyNote.RADIUS_PT, paper)
        // The runs are unturned page pt; the whole sticky area is the region, since the tile clip ends the drawing.
        text.draw(canvas, pageText.runsOf(sticky.id), r, scale, StickyNote.INK_ARGB, borderArgb)
        canvas.restore()
    }
}
