package dev.folio.core.text.spike

import android.graphics.Canvas
import android.graphics.Paint
import dev.folio.core.text.fonts.BundledFontFamily
import dev.folio.core.text.layout.BaselineMethod
import dev.folio.core.text.layout.GridParagraph
import dev.folio.core.text.layout.GridTextLayouter

/**
 * Spike P01-S3: every bundled font on college-ruled paper, one block per font. [compact] shows one
 * sample line per font (screenshot test); otherwise the first probe paragraph (`spike-fonts`).
 * Deleted or promoted by P01-T08.
 */
class FontSpecimen(
    layouter: GridTextLayouter,
    families: List<BundledFontFamily>,
    method: BaselineMethod,
    compact: Boolean,
) {
    private val blocks: List<GridParagraph>
    private val topsPt: FloatArray

    /** Page height in pt covering every block plus one rule of margin. */
    val heightPt: Float

    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RULE_ARGB.toInt() }
    private val margin = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARGIN_ARGB.toInt() }
    private val paper = Paint().apply { color = PAPER_ARGB.toInt() }

    init {
        val unit = BaselineProbe.UNIT_PT
        blocks =
            families.map { family ->
                val text = if (compact) "${family.name}: Hamburgefonstiv 0123 gjpqy" else "${family.name}. ${BaselineProbe.PARAGRAPHS[0]}"
                layouter.layout(text, family, unit, BaselineProbe.BODY_M_CAP, BaselineProbe.WIDTH_PT, method)
            }
        topsPt = FloatArray(blocks.size)
        var top = TOP_RULES * unit
        blocks.forEachIndexed { i, block ->
            topsPt[i] = top
            top += block.heightPt + if (compact) 0f else unit
        }
        heightPt = top + unit
    }

    /** Draws paper, rules, margin line and all blocks at [scale] px per pt with the page origin at 0,0. */
    fun draw(
        canvas: Canvas,
        scale: Float,
    ) {
        val unit = BaselineProbe.UNIT_PT
        val widthPx = WIDTH_PT * scale
        canvas.drawRect(0f, 0f, widthPx, heightPt * scale, paper)
        rule.strokeWidth = RULE_WIDTH_PT * scale
        margin.strokeWidth = RULE_WIDTH_PT * scale
        var y = unit
        while (y < heightPt) {
            canvas.drawLine(0f, y * scale, widthPx, y * scale, rule)
            y += unit
        }
        canvas.drawLine(MARGIN_PT * scale, 0f, MARGIN_PT * scale, heightPt * scale, margin)
        blocks.forEachIndexed { i, block -> block.draw(canvas, MARGIN_PT + TEXT_INSET_PT, topsPt[i], scale) }
    }

    companion object {
        const val WIDTH_PT = 595.28f
        private const val TOP_RULES = 2
        private const val MARGIN_PT = 25f * 72f / 25.4f
        private const val TEXT_INSET_PT = 2f * 72f / 25.4f
        private const val RULE_WIDTH_PT = 0.5f
        private const val RULE_ARGB = 0xFFC9D3E0
        private const val MARGIN_ARGB = 0xFFF2A6A6
        private const val PAPER_ARGB = 0xFFFFFFFF
    }
}
