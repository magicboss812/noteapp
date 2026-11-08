package dev.folio.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.flow.TextRun
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TextRunPainterTest {
    private val pages = TextPages()

    @Test
    fun draw_blockContinuedFromThePageBefore_showsNoDescendersOfTheLineAbove() {
        val run =
            pages
                .text("gyp ".repeat(GLYPH_GROUPS))
                .runs
                .single()
        assertThat(run.layout.lineCount).isAtLeast(3)
        // The page after a break shows lines 1.. of the block; line 0's descenders hang across the top rule.
        val continued = run.copy(firstLine = 1, lineCount = run.layout.lineCount - 1)

        val bitmap = window(continued, continued)

        // The top of line 1's box is above its own glyphs (x height only): any ink there came from line 0.
        val band = px(BAND_UNITS * pages.unitPt)
        assertThat(inkRows(bitmap, 0, band)).isEqualTo(0)
        assertThat(inkRows(bitmap, band, bitmap.height)).isGreaterThan(0)
    }

    @Test
    fun draw_blockContinuedFromThePageBefore_keepsTheTallGlyphsOfItsFirstLine() {
        // No descenders, accented capitals: the continued run draws the window exactly as the whole block does.
        val run =
            pages
                .text("ÅÉÎ HÄL ".repeat(GLYPH_GROUPS))
                .runs
                .single()
        val continued = run.copy(firstLine = 1, lineCount = run.layout.lineCount - 1)

        assertThat(window(continued, continued).sameAs(window(run, continued))).isTrue()
    }

    // Lines 1 and 2 of [run]'s block as [painted] draws them.
    private fun window(
        painted: TextRun,
        run: TextRun,
    ): Bitmap {
        val left = run.leftPt
        val top = run.sliceTopPt
        val bitmap = Bitmap.createBitmap(px(run.widthPt), px(2f * pages.unitPt), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.scale(SCALE, SCALE)
        canvas.translate(-left, -top)
        TextRunPainter().draw(canvas, listOf(painted), RectPt(left, top, left + run.widthPt, top + 2f * pages.unitPt), SCALE, INK, INK)
        return bitmap
    }

    private fun inkRows(
        bitmap: Bitmap,
        from: Int,
        until: Int,
    ): Int =
        (from until until).count { y ->
            (0 until bitmap.width).any { x -> Color.alpha(bitmap.getPixel(x, y)) > INK_ALPHA }
        }

    private fun px(pt: Float): Int = (pt * SCALE).toInt()

    private companion object {
        const val SCALE = 4f
        const val GLYPH_GROUPS = 120
        const val BAND_UNITS = 0.2f
        const val INK_ALPHA = 40
        const val INK = 0xFF000000.toInt()
    }
}
