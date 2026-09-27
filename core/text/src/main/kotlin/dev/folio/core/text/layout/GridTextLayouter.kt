package dev.folio.core.text.layout

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.folio.core.text.fonts.BundledFontFamily
import dev.folio.core.text.fonts.FontMetricsCache
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Canvas as ComposeCanvas

/** How a block's baselines are put on grid rules; compared by spike P01-S3 (ADR-008). */
enum class BaselineMethod {
    /** 07-text-engine.md#line-box as first written: reference scale 4 px/pt, block shifted by the font descent. */
    FONT_DESCENT,

    /**
     * Reference scale chosen so one grid unit is a whole number of px (line pitch exact, no ceil
     * drift), block shifted so the first measured baseline lands on its rule.
     */
    GRID_PITCH,
}

/**
 * One paragraph laid out on the grid: line boxes of one grid unit, baselines on the rule at the
 * bottom of each box (07-text-engine.md#line-box). Page positions are points; the block top sits
 * on a rule.
 */
class GridParagraph internal constructor(
    private val paragraph: Paragraph,
    /** Layout px per pt. */
    val refScale: Float,
    /** Line box height in layout px (a whole number for GRID_PITCH). */
    val lineHeightPx: Float,
    /** Vertical shift in layout px applied to the paragraph so baselines meet rules. */
    val shiftPx: Float,
    /** Grid unit U in pt. */
    val unitPt: Float,
    /** Font size in pt after cap-height normalization. */
    val fontSizePt: Float,
) {
    /** Number of laid out lines. */
    val lineCount: Int get() = paragraph.lineCount

    /** Height in pt: whole line boxes. */
    val heightPt: Float get() = lineCount * unitPt

    /** Baseline of [line] in pt below the block top. */
    fun baselinePt(line: Int): Float = (paragraph.getLineBaseline(line) + shiftPx) / refScale

    /** Draws the block with its top-left at ([leftPt], [topPt]) on a canvas mapping 1 pt to [scale] px. */
    fun draw(
        canvas: android.graphics.Canvas,
        leftPt: Float,
        topPt: Float,
        scale: Float,
        color: Color = Color.Black,
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
 * Lays out plain paragraphs on a grid with cap-height normalized font sizes
 * (07-text-engine.md#line-box, #font-normalization). Spike P01-S3 seed of `BlockLayout`.
 */
class GridTextLayouter(
    private val resolver: FontFamily.Resolver,
    private val metrics: FontMetricsCache,
) {
    /**
     * Lays out [text] in [family] with cap height [capFraction] x [unitPt] and width [widthPt],
     * placing baselines with [method].
     */
    fun layout(
        text: String,
        family: BundledFontFamily,
        unitPt: Float,
        capFraction: Float,
        widthPt: Float,
        method: BaselineMethod,
    ): GridParagraph {
        val proportions = metrics.proportions(family)
        // GRID_PITCH: the line height is an exact whole px count (Compose rounds line heights up, so a
        // float that lands a hair above n would become n + 1 and drift); the scale follows from it.
        val lineHeightPx =
            when (method) {
                BaselineMethod.FONT_DESCENT -> unitPt * REFERENCE_SCALE
                BaselineMethod.GRID_PITCH -> (unitPt * REFERENCE_SCALE).roundToInt().toFloat()
            }
        val refScale = lineHeightPx / unitPt
        val fontSizePt = capFraction * unitPt / proportions.capRatio
        val fontSizePx = fontSizePt * refScale
        val style =
            TextStyle(
                fontFamily = family.fontFamily,
                fontSize = fontSizePx.sp,
                lineHeight = lineHeightPx.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None),
                textMotion = TextMotion.Animated, // linear, subpixel metrics: line breaks never change with zoom
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )
        val paragraph =
            Paragraph(
                text = text,
                style = style,
                constraints = Constraints(maxWidth = ceil(widthPt * refScale).toInt()),
                density = UNIT_DENSITY,
                fontFamilyResolver = resolver,
            )
        val shiftPx =
            when (method) {
                BaselineMethod.FONT_DESCENT -> proportions.descentRatio * fontSizePx
                BaselineMethod.GRID_PITCH -> if (paragraph.lineCount == 0) 0f else lineHeightPx - paragraph.getLineBaseline(0)
            }
        return GridParagraph(paragraph, refScale, lineHeightPx, shiftPx, unitPt, fontSizePt)
    }

    companion object {
        /** Layout px per pt (07-text-engine.md#line-box). */
        const val REFERENCE_SCALE = 4f

        /** 1 sp = 1 px so styles are given in layout px. */
        private val UNIT_DENSITY = Density(density = 1f, fontScale = 1f)
    }
}
