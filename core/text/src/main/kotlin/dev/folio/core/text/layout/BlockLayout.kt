package dev.folio.core.text.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Paragraph
import dev.folio.core.text.markdown.CalloutType
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/** Non-text geometry of a block in pt relative to the block's top-left; the page renderer draws it (P06-T05). */
sealed interface BlockDecoration {
    data object None : BlockDecoration

    /** Quote or callout bar over the full block height. */
    data class QuoteBar(
        val leftPt: Float,
        val widthPt: Float,
        val callout: CalloutType?,
    ) : BlockDecoration

    /** Muted background over the whole block (code blocks). */
    data object CodeBackground : BlockDecoration

    /** Horizontal rule across the block width at [yPt] (the center of its line box). */
    data class Rule(
        val yPt: Float,
    ) : BlockDecoration

    /** Task checkbox square whose bottom sits on the first baseline. */
    data class Checkbox(
        val leftPt: Float,
        val topPt: Float,
        val sizePt: Float,
        val checked: Boolean,
    ) : BlockDecoration
}

/**
 * Text laid out at the grid-pitch reference scale [refScale] px per pt and shifted by [shiftPx] so
 * the first baseline lands on the bottom of its line box (07-text-engine.md#line-box, A-006).
 */
internal class GridText(
    private val paragraph: Paragraph,
    /** The characters laid out (a callout title, a newline, then the text for callouts). */
    val plain: String,
    private val refScale: Float,
    private val shiftPx: Float,
) {
    val lineCount: Int get() = paragraph.lineCount

    fun lineForOffset(offset: Int): Int = paragraph.getLineForOffset(offset)

    fun baselinePt(line: Int): Float = (paragraph.getLineBaseline(line) + shiftPx) / refScale

    /** Right edge of the ink advance of [line] (start-aligned text). */
    fun lineRightPt(line: Int): Float = paragraph.getLineRight(line) / refScale

    fun draw(
        canvas: android.graphics.Canvas,
        leftPt: Float,
        topPt: Float,
        scale: Float,
        color: Color,
    ) {
        canvas.save()
        canvas.translate(leftPt * scale, topPt * scale)
        val k = scale / refScale
        canvas.scale(k, k)
        canvas.translate(0f, shiftPx)
        paragraph.paint(ComposeCanvas(canvas), color = color)
        canvas.restore()
    }
}

/**
 * One Markdown block laid out on the grid (07-text-engine.md#line-box, #blocks): [lineCount] line
 * boxes of [boxUnits] x U each, stacked without gaps, every baseline on the rule at the bottom of
 * its box. Positions are pt relative to the block's top-left, which sits on a rule.
 */
class BlockLayout internal constructor(
    private val text: GridText?,
    private val marker: GridText?,
    /** Grid unit U in pt. */
    val unitPt: Float,
    /** Line box height of every line in U (1, or 2 for H1/H2; more for large free sizes). */
    val boxUnits: Int,
    /** Number of line boxes; 1 for empty paragraphs and rules. */
    val lineCount: Int,
    /** Left edge of the text column. */
    val textLeftPt: Float,
    /** Left edge of the list marker gutter (meaningful when [hasMarker]). */
    val markerLeftPt: Float,
    /** Font size of the block text after cap-height normalization. */
    val fontSizePt: Float,
    /** Bars, backgrounds, rules and checkboxes. */
    val decoration: BlockDecoration,
) {
    /** Height in U: always lineCount x boxUnits. */
    val heightUnits: Int get() = lineCount * boxUnits

    /** Height in pt: whole line boxes. */
    val heightPt: Float get() = heightUnits * unitPt

    /** True when a bullet or number label is drawn in the gutter. */
    val hasMarker: Boolean get() = marker != null

    /** Top of the line box of [line]. */
    fun lineTopPt(line: Int): Float = line * boxUnits * unitPt

    /** Baseline of [line]; blocks without text report the bottom of their line box. */
    fun baselinePt(line: Int): Float = text?.baselinePt(line) ?: ((line + 1) * boxUnits * unitPt)

    /** The text shown in the block's lines (without list labels); empty for blocks without text. */
    val plainText: String get() = text?.plain.orEmpty()

    /** Line that shows character [offset] of [plainText] (0 for blocks without text). */
    fun lineForOffset(offset: Int): Int = text?.lineForOffset(offset) ?: 0

    /** Baseline of the marker label, null without a marker. */
    fun markerBaselinePt(): Float? = marker?.baselinePt(0)

    /**
     * Draws text and marker with the block's top-left at ([leftPt], [topPt]) on a canvas mapping 1 pt
     * to [scale] px; [decoration] is left to the caller.
     */
    fun draw(
        canvas: android.graphics.Canvas,
        leftPt: Float,
        topPt: Float,
        scale: Float,
        color: Color = Color.Black,
    ) {
        text?.draw(canvas, leftPt + textLeftPt, topPt, scale, color)
        marker?.draw(canvas, leftPt + markerLeftPt, topPt, scale, color)
    }
}
