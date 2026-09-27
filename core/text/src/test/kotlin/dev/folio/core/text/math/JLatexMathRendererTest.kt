package dev.folio.core.text.math

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.common.Outcome
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.ceil

/** ADR-007 regression: corpus coverage and ink inside the padded box (P01-S4 numbers). */
@RunWith(AndroidJUnit4::class)
class JLatexMathRendererTest {
    private val renderer = JLatexMathRenderer(ApplicationProvider.getApplicationContext())
    private val corpus =
        File("../../testdata/math/corpus.txt")
            .readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }

    @Test
    fun layout_corpus_atLeast95PercentParse() {
        val parsed = corpus.count { renderer.layout(it, SIZE_PX, BLACK, display = false) is Outcome.Success }

        assertThat(corpus).hasSize(40)
        assertThat(parsed * 100 / corpus.size).isAtLeast(95)
    }

    @Test
    fun layout_invalidLatex_failure() {
        assertThat(renderer.layout("\\notacommand{x}", SIZE_PX, BLACK, display = false)).isInstanceOf(Outcome.Failure::class.java)
    }

    @Test
    fun draw_corpus_inkStaysInsidePaddedBox() {
        for (latex in corpus) {
            val layout = (renderer.layout(latex, SIZE_PX, BLACK, display = false) as? Outcome.Success)?.value ?: continue
            assertWithMessage(latex).that(inkOverflowPx(layout)).isAtMost(MAX_OVERFLOW_PX)
        }
    }

    /** How far ink with alpha >= 25% reaches outside the box, in px. */
    private fun inkOverflowPx(layout: MathLayout): Float {
        val box = layout.box
        val width = ceil(box.widthPx).toInt() + 2 * MARGIN
        val height = ceil(box.ascentPx + box.depthPx).toInt() + 2 * MARGIN
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bitmap), MARGIN.toFloat(), MARGIN + box.ascentPx)
        var top = height
        var bottom = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (bitmap.getPixel(x, y) ushr 24 < INK_ALPHA) continue
                top = minOf(top, y)
                bottom = maxOf(bottom, y)
                left = minOf(left, x)
                right = maxOf(right, x)
            }
        }
        if (right < 0) return 0f
        return maxOf(
            0f,
            MARGIN - top.toFloat(),
            MARGIN - left.toFloat(),
            right + 1 - (MARGIN + box.widthPx),
            bottom + 1 - (MARGIN + box.ascentPx + box.depthPx),
        )
    }

    private companion object {
        const val SIZE_PX = 50f
        const val BLACK = 0xFF000000.toInt()
        const val MARGIN = 16
        const val INK_ALPHA = 0x40
        const val MAX_OVERFLOW_PX = 1f
    }
}
