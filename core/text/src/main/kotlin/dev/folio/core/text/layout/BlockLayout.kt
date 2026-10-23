package dev.folio.core.text.layout

import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.font.FontFamily
import dev.folio.core.text.markdown.CalloutType
import dev.folio.core.text.math.MathLayout
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
 * Adapter that lets block layouts paint onto an Android canvas. The Compose canvas wrapper is the only
 * per-draw allocation of painting text, so a drawing thread binds its canvas once and paints every block
 * through it. Not thread-safe: one per drawing thread; [release] when the canvas is done.
 */
class TextCanvas {
    private var native: android.graphics.Canvas? = null
    private var wrapped: ComposeCanvas? = null

    /** Binds [canvas]; binding the canvas that is already bound reuses its wrapper. */
    fun bind(canvas: android.graphics.Canvas): TextCanvas {
        if (native !== canvas) {
            wrapped = ComposeCanvas(canvas)
            native = canvas
        }
        return this
    }

    /** Forgets the bound canvas so a finished tile bitmap is not retained. */
    fun release() {
        native = null
        wrapped = null
    }

    internal fun compose(): ComposeCanvas = checkNotNull(wrapped) { "TextCanvas.bind was not called" }

    internal fun native(): android.graphics.Canvas = checkNotNull(native) { "TextCanvas.bind was not called" }

    /** Paint of the tall-formula dots, recolored per draw (this canvas belongs to one thread). */
    internal fun dots(argb: Int): Paint {
        val paint = dotPaint ?: Paint(Paint.ANTI_ALIAS_FLAG).also { dotPaint = it }
        paint.color = argb
        paint.alpha = TALL_DOT_ALPHA
        return paint
    }

    private var dotPaint: Paint? = null

    private companion object {
        const val TALL_DOT_ALPHA = 0x80
    }
}

/**
 * A formula of a block (07-text-engine.md#math): the left edge of its box and its baseline in pt from the block's
 * top-left, its [ascentPt] and [depthPt] as drawn. [tall] marks inline math that needed more than the minimum scale
 * to fit the line box and is drawn with a dotted underline (use block math instead).
 */
class MathPlacement internal constructor(
    private val layout: MathLayout,
    /** pt per px of [layout]: the fit scale divided by the layout's reference scale. */
    private val ptPerPx: Float,
    val leftPt: Float,
    val baselinePt: Float,
    val tall: Boolean,
) {
    val widthPt: Float get() = layout.box.widthPx * ptPerPx
    val ascentPt: Float get() = layout.box.ascentPx * ptPerPx
    val depthPt: Float get() = layout.box.depthPx * ptPerPx

    // HOT PATH: once per formula and tile render.
    internal fun draw(
        canvas: android.graphics.Canvas,
        leftPt: Float,
        topPt: Float,
        scale: Float,
        argb: Int,
        dots: Paint,
    ) {
        val save = canvas.save()
        canvas.translate((leftPt + this.leftPt) * scale, (topPt + baselinePt) * scale)
        val k = scale * ptPerPx
        canvas.scale(k, k)
        layout.draw(canvas, 0f, 0f, argb)
        canvas.restoreToCount(save)
        if (tall) {
            val y = (topPt + baselinePt + depthPt) * scale
            val step = TALL_DOT_STEP_PT * scale
            var x = (leftPt + this.leftPt) * scale + step / 2f
            val right = (leftPt + this.leftPt + widthPt) * scale
            while (x < right) {
                canvas.drawCircle(x, y, step * TALL_DOT_RADIUS, dots)
                x += step
            }
        }
    }

    private companion object {
        const val TALL_DOT_STEP_PT = 2f
        const val TALL_DOT_RADIUS = 0.25f // of the step
    }
}

/** LaTeX of [plainStart]..[plainEnd] of a block's plain text that did not parse, with the parser's [message]. */
data class MathError(
    val plainStart: Int,
    val plainEnd: Int,
    val message: String,
)

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

    /** Character nearest to [xPt] (from the text's left edge) on the middle of [line]. */
    fun offsetAt(
        xPt: Float,
        line: Int,
    ): Int {
        val l = line.coerceIn(0, paragraph.lineCount - 1)
        val middle = (paragraph.getLineTop(l) + paragraph.getLineBottom(l)) / 2f
        return paragraph.getOffsetForPosition(Offset(xPt * refScale, middle))
    }

    fun baselinePt(line: Int): Float = (paragraph.getLineBaseline(line) + shiftPx) / refScale

    /** Box of placeholder [index] in pt from the text's top-left (bottom = its baseline), null when it is not shown. */
    fun placeholderPt(index: Int): Rect? =
        paragraph.placeholderRects.getOrNull(index)?.let {
            Rect(it.left / refScale, (it.top + shiftPx) / refScale, it.right / refScale, (it.bottom + shiftPx) / refScale)
        }

    /** Right edge of the ink advance of [line] (start-aligned text). */
    fun lineRightPt(line: Int): Float = paragraph.getLineRight(line) / refScale

    // HOT PATH: runs once per block and tile render; no allocation here beyond what Paragraph.paint does itself.
    fun draw(
        target: TextCanvas,
        leftPt: Float,
        topPt: Float,
        scale: Float,
        color: Color,
    ) {
        val canvas = target.compose()
        canvas.save()
        canvas.translate(leftPt * scale, topPt * scale)
        val k = scale / refScale
        canvas.scale(k, k)
        canvas.translate(0f, shiftPx)
        paragraph.paint(canvas, color = color)
        canvas.restore()
    }
}

/** The font a block's text is set in: [family] at weight bold or regular. */
class BlockFace internal constructor(
    internal val family: FontFamily,
    val bold: Boolean,
)

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
    /** The font of the text (null for blocks without text); a live field resolves it with [typeface]. */
    val face: BlockFace? = null,
    /** Inline formulas in text order, or the one formula of a math block. */
    val maths: List<MathPlacement> = emptyList(),
    /** LaTeX that did not parse; its source is shown in red monospace. */
    val mathErrors: List<MathError> = emptyList(),
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

    /**
     * Offset into [plainText] of the character nearest to [xPt] (from the block's left edge) on [line]; 0 for blocks
     * without text. A point left of the text gives the start of the line, one right of it the end.
     */
    fun plainOffsetAt(
        xPt: Float,
        line: Int,
    ): Int = text?.offsetAt(xPt - textLeftPt, line) ?: 0

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
    ) = draw(TextCanvas().bind(canvas), leftPt, topPt, scale, color, color.toArgb(), drawMath = true)

    /** [draw] through a reusable [target] with a plain ARGB color: the page renderer's path (no Compose types needed). */
    fun paint(
        target: TextCanvas,
        leftPt: Float,
        topPt: Float,
        scale: Float,
        argb: Int,
    ) = draw(target, leftPt, topPt, scale, Color(argb), argb, drawMath = true)

    /** Text and marker only, without [maths] (the grid suite records text baselines alone). */
    internal fun drawText(
        canvas: android.graphics.Canvas,
        leftPt: Float,
        topPt: Float,
        scale: Float,
    ) = draw(TextCanvas().bind(canvas), leftPt, topPt, scale, Color.Black, Color.Black.toArgb(), drawMath = false)

    // HOT PATH: once per block and tile render.
    private fun draw(
        target: TextCanvas,
        leftPt: Float,
        topPt: Float,
        scale: Float,
        color: Color,
        argb: Int,
        drawMath: Boolean,
    ) {
        text?.draw(target, leftPt + textLeftPt, topPt, scale, color)
        marker?.draw(target, leftPt + markerLeftPt, topPt, scale, color)
        if (!drawMath || maths.isEmpty()) return
        val canvas = target.native()
        for (i in maths.indices) maths[i].draw(canvas, leftPt, topPt, scale, argb, target.dots(argb))
    }
}
