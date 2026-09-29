package dev.folio.core.render.template

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.model.AssetId
import dev.folio.core.model.GridUnit
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.Template
import dev.folio.core.model.TemplateKind
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class TemplateRendererTest {
    private val customAsset = AssetId("custom-template")
    private val renderer = TemplateRenderer { if (it == customAsset) customImage() else null }

    @Test
    fun screenshot_everyTemplate_a4Portrait() = captureAll(PageSpec.Fixed(PaperSize.A4, Orientation.PORTRAIT), "A4portrait")

    @Test
    fun screenshot_everyTemplate_a4Landscape() = captureAll(PageSpec.Fixed(PaperSize.A4, Orientation.LANDSCAPE), "A4landscape")

    @Test
    fun screenshot_everyTemplate_a5Portrait() = captureAll(PageSpec.Fixed(PaperSize.A5, Orientation.PORTRAIT), "A5portrait")

    @Test
    fun screenshot_infinitePages_repeatBeyondOriginFrame() {
        val spec = PageSpec.Infinite(PageSpec.Fixed(PaperSize.A5, Orientation.PORTRAIT))
        val region = RectF(-200f, -300f, 700f, 900f)
        for (kind in listOf(TemplateKind.GRAPH_AXES, TemplateKind.PLANNER_WEEKLY)) {
            render(TemplatePresets.default(kind), spec, region, SCALE).captureRoboImage("src/test/screenshots/Template_infinite_$kind.png")
        }
    }

    @Test
    fun lined_rules_lieOnGridRulesFromTopMarginToBottomMargin() {
        val template = TemplatePresets.default(TemplateKind.LINED)
        val grid = GridUnit.of(template)
        val spec = PageSpec.Fixed(PaperSize.A4, Orientation.PORTRAIT)
        val bitmap = render(template, spec, fullPage(spec), SCALE)
        val x = px(400f)

        for (n in 0..3) {
            assertWithMessage("rule $n").that(isInk(bitmap, x, px(grid.ruleYPt(n)))).isTrue()
            assertWithMessage("between $n").that(isInk(bitmap, x, px(grid.ruleYPt(n) + grid.unitPt / 2))).isFalse()
        }
        assertWithMessage("above top margin").that(isInk(bitmap, x, px(grid.ruleYPt(-1)))).isFalse()
        assertWithMessage("margin line").that(isInk(bitmap, px(grid.originXPt), px(grid.ruleYPt(0) + grid.unitPt / 2))).isTrue()
        val lastRule = ((spec.heightPt - 12f * MM - grid.originYPt) / grid.unitPt).toInt()
        assertWithMessage("last rule").that(isInk(bitmap, x, px(grid.ruleYPt(lastRule)))).isTrue()
        assertWithMessage("below bottom margin").that(isInk(bitmap, x, px(grid.ruleYPt(lastRule + 1)))).isFalse()
    }

    @Test
    fun grid_columnsAndRows_lieOnGridUnit() {
        val template = TemplatePresets.default(TemplateKind.GRID).copy(marginLeftPt = 10f, marginTopPt = 20f)
        val grid = GridUnit.of(template)
        val spec = PageSpec.Fixed(PaperSize.A5, Orientation.PORTRAIT)
        val bitmap = render(template, spec, fullPage(spec), SCALE)
        val between = grid.unitPt / 2

        assertThat(isInk(bitmap, px(grid.columnXPt(5)), px(grid.ruleYPt(3) + between))).isTrue()
        assertThat(isInk(bitmap, px(grid.columnXPt(5) + between), px(grid.ruleYPt(3)))).isTrue()
        assertThat(isInk(bitmap, px(grid.columnXPt(5) + between), px(grid.ruleYPt(3) + between))).isFalse()
    }

    @Test
    fun grid_a4Width42Cells_drawsColumnOnRightEdge() {
        val template = TemplatePresets.default(TemplateKind.GRID)
        val spec = PageSpec.Fixed(PaperSize.A4, Orientation.PORTRAIT)
        val bitmap = render(template, spec, fullPage(spec), SCALE)
        val grid = GridUnit.of(template)

        assertThat(isInk(bitmap, bitmap.width - 1, px(grid.ruleYPt(3) + grid.unitPt / 2))).isTrue()
    }

    @Test
    fun draw_regionOutsideFixedPage_drawsNothing() {
        val spec = PageSpec.Fixed(PaperSize.A5, Orientation.PORTRAIT)
        val bitmap = render(TemplatePresets.default(TemplateKind.GRID), spec, RectF(spec.widthPt + 1f, 0f, spec.widthPt + 50f, 50f), SCALE)

        assertThat(countInk(bitmap)).isEqualTo(0)
    }

    @Test
    fun draw_hostileDecodedValues_finishesAndDrawsDefaults() {
        val spec = PageSpec.Fixed(PaperSize.A5, Orientation.PORTRAIT)
        for (kind in TemplateKind.entries) {
            val bad = Template(kind, Float.NaN, LINE, Float.NEGATIVE_INFINITY, Float.NaN, null, 0f)
            render(bad, spec, fullPage(spec), SCALE)
        }
        val tiny = render(TemplatePresets.default(TemplateKind.DOTTED).copy(spacingPt = 0f), spec, fullPage(spec), SCALE)

        assertThat(countInk(tiny)).isGreaterThan(0)
    }

    private fun captureAll(
        spec: PageSpec,
        name: String,
    ) {
        for (kind in TemplateKind.entries) {
            val template = TemplatePresets.default(kind, customAsset)
            render(template, spec, fullPage(spec), SCALE).captureRoboImage("src/test/screenshots/Template_${name}_$kind.png")
        }
    }

    private fun render(
        template: Template,
        spec: PageSpec,
        regionPt: RectF,
        scale: Float,
    ): Bitmap {
        val bitmap =
            Bitmap.createBitmap(
                ceil(regionPt.width() * scale).toInt(),
                ceil(regionPt.height() * scale).toInt(),
                Bitmap.Config.ARGB_8888,
            )
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        canvas.scale(scale, scale)
        canvas.translate(-regionPt.left, -regionPt.top)
        renderer.draw(canvas, template, spec, regionPt, scale)
        return bitmap
    }

    private fun fullPage(spec: PageSpec) = RectF(0f, 0f, spec.widthPt, spec.heightPt)

    private fun px(pt: Float): Int = (pt * SCALE).roundToInt()

    private fun isInk(
        bitmap: Bitmap,
        x: Int,
        y: Int,
    ): Boolean = bitmap.getPixel(x, y) != Color.WHITE

    private fun countInk(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.count { it != Color.WHITE }
    }

    /** A 60x80 px stand-in for a decoded custom template: tinted paper with a frame and a diagonal. */
    private fun customImage(): Bitmap {
        val bitmap = Bitmap.createBitmap(60, 80, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(0xFFF4F0E6.toInt())
        val paint = Paint().apply { color = 0xFF6B8FB8.toInt() }
        paint.style = Paint.Style.STROKE
        canvas.drawRect(4f, 4f, 56f, 76f, paint)
        canvas.drawLine(4f, 4f, 56f, 76f, paint)
        return bitmap
    }

    private companion object {
        const val SCALE = 1.5f
        const val MM = 72f / 25.4f
        const val LINE = 0xFFC9D3E0.toInt()
    }
}
