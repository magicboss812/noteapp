package dev.folio.core.text.spike

import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.math.MathBox
import dev.folio.core.text.math.MathLayout
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P01-S4 host part: probe math, and option C (pure Java) on Robolectric native graphics. RaTeX
 * (option A) loads an Android-only native library, so it is measured by MathProbeInstrumentedTest.
 */
@RunWith(AndroidJUnit4::class)
class MathProbeTest {
    private val corpus = MathProbe.parseCorpus(File("../../testdata/math/corpus.txt").readText())

    @Test
    fun parseCorpus_commentsAndBlankLines_skipped() {
        assertThat(MathProbe.parseCorpus("# c\n\nx^2\n  \\frac{a}{b}  \n")).containsExactly("x^2", "\\frac{a}{b}").inOrder()
        assertThat(corpus).hasSize(40)
    }

    @Test
    fun inkOverflowPx_inkInsideBox_zero() {
        assertThat(MathProbe.inkOverflowPx(RectLayout(MathBox(40f, 20f, 10f), overshootPx = 0f))).isEqualTo(0f)
    }

    @Test
    fun inkOverflowPx_inkBelowDepth_reportsOvershoot() {
        assertThat(MathProbe.inkOverflowPx(RectLayout(MathBox(40f, 20f, 10f), overshootPx = 5f))).isWithin(1f).of(5f)
    }

    @Test
    fun jlatexmath_corpus_writesHostTable() {
        val result = MathProbe.run(JLatexMathRenderer(ApplicationProvider.getApplicationContext()), corpus, repeats = 3)

        File("build/reports/p01-s4").mkdirs()
        File("build/reports/p01-s4/math-robolectric.md").writeText(MathProbe.table(listOf(result)))
        assertThat(result.total).isEqualTo(40)
    }

    /** Fills its box, extending [overshootPx] below the depth. */
    private class RectLayout(
        override val box: MathBox,
        private val overshootPx: Float,
    ) : MathLayout {
        override fun draw(
            canvas: Canvas,
            xPx: Float,
            baselineYPx: Float,
        ) {
            canvas.drawRect(xPx, baselineYPx - box.ascentPx, xPx + box.widthPx, baselineYPx + box.depthPx + overshootPx, Paint())
        }
    }
}
