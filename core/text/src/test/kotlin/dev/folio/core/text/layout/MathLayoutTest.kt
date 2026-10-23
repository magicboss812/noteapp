package dev.folio.core.text.layout

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.GridUnit
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.core.text.math.JLatexMathRenderer
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

/** Inline and block math in [BlockLayouter] (07-text-engine.md#math). */
@RunWith(AndroidJUnit4::class)
class MathLayoutTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val resolver = createFontFamilyResolver(context)
    private val metrics = FontMetricsCache(resolver)
    private val layouter = BlockLayouter(resolver, metrics, math = JLatexMathRenderer(context))
    private val grid = LayoutGrid.of(GridUnit(GridUnit.COLLEGE_UNIT_PT, 0f, 36f, snapsColumns = false))
    private val unit = grid.unitPt

    private fun layout(
        markdown: String,
        with: BlockLayouter = layouter,
    ): BlockLayout =
        with.build(
            MarkdownDocument
                .parse(markdown)
                .blocks
                .single()
                .content,
            WIDTH_PT,
            FlowTextStyle(),
            grid,
        )

    @Test
    fun inlineMath_formulaOnTheBaseline_fitsTheLineBox() {
        val result = layout("Area \$\\pi r^2\$ of a circle")

        val formula = result.maths.single()
        assertThat(result.lineCount).isEqualTo(1)
        assertThat(formula.baselinePt).isWithin(TOLERANCE_PT).of(unit)
        assertThat(formula.ascentPt).isAtMost(0.78f * unit + TOLERANCE_PT)
        assertThat(formula.depthPt).isAtMost(0.30f * unit + TOLERANCE_PT)
        assertThat(formula.leftPt).isGreaterThan(0f)
        assertThat(formula.tall).isFalse()
    }

    @Test
    fun inlineMath_tallFormula_scaledToMinimumAndFlagged() {
        val result = layout("Tall \$\\frac{1}{1 + \\frac{1}{1 + \\frac{1}{x}}}\$ formula")

        val formula = result.maths.single()
        assertThat(formula.tall).isTrue()
        assertThat(formula.baselinePt).isWithin(TOLERANCE_PT).of(unit)
        assertThat(result.heightPt).isEqualTo(unit) // the line box keeps its height
    }

    @Test
    fun inlineMath_wrappedText_formulaOnItsOwnLine() {
        val result = layout("word ".repeat(40) + "\$x^2\$ " + "word ".repeat(10))

        val formula = result.maths.single()
        val line = Math.round(formula.baselinePt / unit)
        assertThat(line).isAtLeast(2)
        assertThat(formula.baselinePt).isWithin(TOLERANCE_PT).of(line * unit)
    }

    @Test
    fun inlineMath_invalidLatex_reportsErrorWithoutFormula() {
        val result = layout("Bad \$\\notacommand{x}\$ here")

        assertThat(result.maths).isEmpty()
        val error = result.mathErrors.single()
        assertThat(result.plainText.substring(error.plainStart, error.plainEnd)).isEqualTo("\\notacommand{x}")
        assertThat(error.message).isNotEmpty()
    }

    @Test
    fun inlineMath_withoutRenderer_italicSource() {
        val result = layout("Area \$\\pi r^2\$", with = BlockLayouter(resolver, metrics))

        assertThat(result.maths).isEmpty()
        assertThat(result.plainText).contains("\\pi r^2")
    }

    @Test
    fun blockMath_wholeLines_formulaCenteredInside() {
        val result = layout("\$\$\n\\int_0^1 x^2 \\, dx = \\frac{1}{3}\n\$\$")

        val formula = result.maths.single()
        assertThat(result.boxUnits).isEqualTo(1)
        assertThat(result.heightPt).isEqualTo(result.lineCount * unit)
        val top = formula.baselinePt - formula.ascentPt
        val bottom = formula.baselinePt + formula.depthPt
        assertThat(top).isGreaterThan(0f)
        assertThat(bottom).isLessThan(result.heightPt)
        assertThat(top).isWithin(TOLERANCE_PT).of(result.heightPt - bottom)
        assertThat(formula.leftPt + formula.widthPt / 2f).isWithin(TOLERANCE_PT).of(WIDTH_PT / 2f)
        // ceil((height + U/2) / U) lines
        assertThat(result.lineCount).isEqualTo(ceil((bottom - top + unit / 2f) / unit).toInt())
    }

    @Test
    fun blockMath_wideFormula_scaledToTheWidth() {
        val result = layout("\$\$\n${terms(20)}\n\$\$")

        val formula = result.maths.single()
        assertThat(formula.widthPt).isWithin(TOLERANCE_PT).of(WIDTH_PT)
        assertThat(formula.leftPt).isWithin(TOLERANCE_PT).of(0f)
    }

    @Test
    fun blockMath_tooWideForMinimumScale_hangsOutOnTheRight() {
        val natural = layout("\$\$\n${terms(2)}\n\$\$").maths.single() // fits; same glyph heights ("+" included)
        val result = layout("\$\$\n${terms(40)}\n\$\$")

        val formula = result.maths.single()
        assertThat(formula.widthPt).isGreaterThan(WIDTH_PT)
        assertThat(formula.leftPt).isEqualTo(0f)
        assertThat(formula.ascentPt / natural.ascentPt).isWithin(SCALE_TOLERANCE).of(0.6f)
    }

    private fun terms(n: Int): String = (1..n).joinToString(" + ") { "x_{$it}" }

    @Test
    fun blockMath_invalidLatex_redSourceWithError() {
        val result = layout("\$\$\n\\frac{\\notacommand\n\$\$")

        assertThat(result.maths).isEmpty()
        assertThat(result.plainText).isEqualTo("\\frac{\\notacommand")
        assertThat(result.mathErrors.single().plainEnd).isEqualTo(result.plainText.length)
        assertThat(result.decoration).isEqualTo(BlockDecoration.CodeBackground)
    }

    @Test
    fun draw_inlineMath_inksTheFormulaInTheTextColor() {
        val result = layout("\$\\sum_{i=1}^{n} i\$")
        val formula = result.maths.single()
        val scale = 4f
        val bitmap = Bitmap.createBitmap(ceil(WIDTH_PT * scale).toInt(), ceil(unit * 2 * scale).toInt(), Bitmap.Config.ARGB_8888)

        result.paint(TextCanvas().bind(Canvas(bitmap)), 0f, 0f, scale, RED)

        val y = ((formula.baselinePt - formula.ascentPt / 2f) * scale).toInt()
        val colored =
            (0 until bitmap.width).count { x ->
                val pixel = bitmap.getPixel(x, y)
                Color.alpha(pixel) > INK_ALPHA && Color.red(pixel) > Color.blue(pixel) + INK_ALPHA
            }
        assertThat(colored).isGreaterThan(0)
    }

    private companion object {
        const val WIDTH_PT = 480f
        const val TOLERANCE_PT = 0.05f
        const val SCALE_TOLERANCE = 0.05f
        const val RED = 0xFFE00000.toInt()
        const val INK_ALPHA = 0x40
    }
}
