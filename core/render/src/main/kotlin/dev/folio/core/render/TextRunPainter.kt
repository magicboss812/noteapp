package dev.folio.core.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.annotation.WorkerThread
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.flow.TextRun
import dev.folio.core.text.layout.BlockDecoration
import dev.folio.core.text.layout.TextCanvas
import dev.folio.core.text.markdown.CalloutType
import kotlin.math.max
import kotlin.math.min

/**
 * Paints the text runs of a frame from cached block layouts (07-text-engine.md#rendering): block decorations (quote
 * and callout bars, code backgrounds, rules, task checkboxes) first, then the text and list marker. The canvas maps
 * page pt to px. Not thread-safe: one per [PageRenderer].
 */
internal class TextRunPainter {
    private val target = TextCanvas()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val rect = RectF()
    private val path = Path()

    /** Paints the runs of [runs] that reach [regionPt]; [scale] px per pt (only used for hairline widths). */
    @WorkerThread
    fun draw(
        canvas: Canvas,
        runs: List<TextRun>,
        regionPt: RectPt,
        scale: Float,
        textArgb: Int,
    ) {
        // HOT PATH: runs once per frame and tile render on a render thread; no allocation per run.
        if (runs.isEmpty()) return
        target.bind(canvas)
        for (i in runs.indices) {
            val run = runs[i]
            if (!run.bounds.intersects(regionPt)) continue
            decorate(canvas, run, scale, textArgb)
            // A block continued in another frame shows only its own line boxes; the rest of the paragraph is clipped away.
            val clipped = run.partial
            if (clipped) {
                canvas.save()
                val overhang = run.overhangPt
                canvas.clipRect(run.leftPt - overhang, run.sliceTopPt, run.leftPt + run.widthPt + overhang, run.sliceBottomPt + overhang)
            }
            run.layout.paint(target, run.leftPt, run.topPt, 1f, textArgb)
            if (clipped) canvas.restore()
        }
    }

    /** Drops the bound canvas so a finished tile bitmap is not retained. */
    fun release() = target.release()

    private fun decorate(
        canvas: Canvas,
        run: TextRun,
        scale: Float,
        textArgb: Int,
    ) {
        val layout = run.layout
        val unit = layout.unitPt
        when (val d = layout.decoration) {
            BlockDecoration.None -> {
                Unit
            }

            BlockDecoration.CodeBackground -> {
                fill.color = run.codeBackgroundArgb
                rect.set(run.leftPt, run.sliceTopPt, run.leftPt + run.widthPt, run.sliceBottomPt)
                canvas.drawRoundRect(rect, unit * CODE_RADIUS_UNITS, unit * CODE_RADIUS_UNITS, fill)
            }

            is BlockDecoration.QuoteBar -> {
                val type = d.callout
                fill.color = barArgb(type)
                val barLeft = run.leftPt + d.leftPt
                rect.set(barLeft, run.sliceTopPt, barLeft + d.widthPt, run.sliceBottomPt)
                canvas.drawRect(rect, fill)
                if (type != null && run.firstLine == 0) callout(canvas, run, d, type)
            }

            is BlockDecoration.Rule -> {
                line.color = MUTED_ARGB
                line.strokeWidth = max(RULE_PT, 1f / scale)
                line.strokeCap = Paint.Cap.BUTT
                val y = run.topPt + d.yPt
                canvas.drawLine(run.leftPt, y, run.leftPt + run.widthPt, y, line)
            }

            is BlockDecoration.Checkbox -> {
                if (run.firstLine == 0) checkbox(canvas, run.leftPt + d.leftPt, run.topPt + d.topPt, d, scale, textArgb)
            }
        }
    }

    /** Round badge in the gutter beside the callout title, tinted per type. */
    private fun callout(
        canvas: Canvas,
        run: TextRun,
        bar: BlockDecoration.QuoteBar,
        type: CalloutType,
    ) {
        val layout = run.layout
        val gutterLeft = run.leftPt + bar.leftPt + bar.widthPt
        val gutter = run.leftPt + layout.textLeftPt - gutterLeft
        val diameter = min(gutter * BADGE_GUTTER_SHARE, layout.fontSizePt * BADGE_FONT_SHARE)
        if (diameter <= 0f) return
        val cx = gutterLeft + gutter / 2f
        val cy = run.topPt + layout.baselinePt(0) - layout.fontSizePt * BADGE_LIFT
        fill.color = barArgb(type)
        canvas.drawCircle(cx, cy, diameter / 2f, fill)
        glyph.color = WHITE_ARGB
        glyph.textSize = diameter * GLYPH_SHARE
        glyph.textAlign = Paint.Align.CENTER
        canvas.drawText(badgeGlyph(type), cx, cy + glyph.textSize * GLYPH_DROP, glyph)
    }

    private fun checkbox(
        canvas: Canvas,
        leftPt: Float,
        topPt: Float,
        box: BlockDecoration.Checkbox,
        scale: Float,
        textArgb: Int,
    ) {
        val size = box.sizePt
        val radius = size * CHECKBOX_RADIUS
        rect.set(leftPt, topPt, leftPt + size, topPt + size)
        if (box.checked) {
            fill.color = ACCENT_ARGB
            canvas.drawRoundRect(rect, radius, radius, fill)
            line.color = WHITE_ARGB
            line.strokeWidth = size * CHECK_STROKE
            line.strokeCap = Paint.Cap.ROUND
            line.strokeJoin = Paint.Join.ROUND
            path.rewind()
            path.moveTo(leftPt + size * CHECK_X0, topPt + size * CHECK_Y0)
            path.lineTo(leftPt + size * CHECK_X1, topPt + size * CHECK_Y1)
            path.lineTo(leftPt + size * CHECK_X2, topPt + size * CHECK_Y2)
            canvas.drawPath(path, line)
            line.strokeJoin = Paint.Join.MITER
        } else {
            line.color = textArgb
            line.strokeWidth = max(size * OUTLINE_STROKE, 1f / scale)
            line.strokeCap = Paint.Cap.BUTT
            val inset = line.strokeWidth / 2f
            rect.inset(inset, inset)
            canvas.drawRoundRect(rect, radius, radius, line)
        }
    }

    private companion object {
        const val CODE_RADIUS_UNITS = 0.2f
        const val RULE_PT = 0.75f
        const val BADGE_GUTTER_SHARE = 0.85f
        const val BADGE_FONT_SHARE = 0.7f
        const val BADGE_LIFT = 0.36f
        const val GLYPH_SHARE = 0.78f
        const val GLYPH_DROP = 0.35f
        const val CHECKBOX_RADIUS = 0.18f
        const val OUTLINE_STROKE = 0.1f
        const val CHECK_STROKE = 0.13f
        const val CHECK_X0 = 0.22f
        const val CHECK_Y0 = 0.52f
        const val CHECK_X1 = 0.43f
        const val CHECK_Y1 = 0.72f
        const val CHECK_X2 = 0.78f
        const val CHECK_Y2 = 0.30f
        const val MUTED_ARGB = 0xFF8E96A3.toInt()
        const val ACCENT_ARGB = 0xFF2F6FDE.toInt()
        const val WHITE_ARGB = 0xFFFFFFFF.toInt()

        fun barArgb(type: CalloutType?): Int =
            when (type) {
                null -> MUTED_ARGB
                CalloutType.NOTE -> ACCENT_ARGB
                CalloutType.TIP -> 0xFF2E9E5B.toInt()
                CalloutType.IMPORTANT -> 0xFF8A4FD6.toInt()
                CalloutType.WARNING -> 0xFFD98E04.toInt()
                CalloutType.CAUTION -> 0xFFD64545.toInt()
            }

        fun badgeGlyph(type: CalloutType): String =
            when (type) {
                CalloutType.NOTE -> "i"
                CalloutType.TIP -> "*"
                CalloutType.IMPORTANT, CalloutType.WARNING -> "!"
                CalloutType.CAUTION -> "x"
            }
    }
}
