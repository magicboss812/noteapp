package dev.folio.core.render

import android.graphics.Canvas
import androidx.annotation.MainThread
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.flow.TextRun

/**
 * Paints text runs over the page tiles, the way tiles paint them (A-058): the canvas overlay draws the edited block with
 * it. The canvas maps page pt to px; [draw] binds and releases it per call. Not thread-safe: one per view.
 */
class OverlayTextPainter {
    private val painter = TextRunPainter()

    /** Paints the runs of [runs] that reach [regionPt] in [textArgb]; [scale] px per pt (hairline widths). */
    @MainThread
    fun draw(
        canvas: Canvas,
        runs: List<TextRun>,
        regionPt: RectPt,
        scale: Float,
        textArgb: Int,
    ) {
        // HOT PATH: runs on every overlay frame while a block is edited.
        painter.draw(canvas, runs, regionPt, scale, textArgb, textArgb)
        painter.release()
    }
}
