package dev.folio.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.shape.ShapeGeometry
import dev.folio.core.testing.ModelFixtures
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ShapePainterScreenshotTest {
    private val renderer = PageRenderer()

    @Test
    fun screenshot_allKindsAndStyles() {
        // Rows: kinds. Columns: thin solid, dashed, tinted fill, solid fill, thick rotated.
        val shapes = ArrayList<Shape>()
        KINDS.forEachIndexed { row, kind ->
            STYLES.forEachIndexed { col, style ->
                val left = MARGIN + col * CELL
                val top = MARGIN + row * CELL
                val pts = points(kind, left, top, left + CELL - GAP, top + CELL - GAP)
                val s = ShapeGeometry.create(ObjectId("s$row$col"), kind, pts, style.stroke, style.fill)
                shapes += if (style.rotationDeg != 0f) ShapeGeometry.withPoints(s, s.points, style.rotationDeg) else s
            }
        }
        val page = ModelFixtures.page("p", shapes)
        val region = RectPt(0f, 0f, MARGIN * 2 + STYLES.size * CELL, MARGIN * 2 + KINDS.size * CELL)
        val bitmap = Bitmap.createBitmap((region.widthPt * SCALE).toInt(), (region.heightPt * SCALE).toInt(), Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap), page.toRef(), PageContent.of(page), region, SCALE, RenderTarget.EXPORT_RASTER)

        // Solid fill of the rectangle in column 3 (row 2) paints its inside.
        val inside = bitmap.getPixel(((MARGIN + 3 * CELL + CELL / 2) * SCALE).toInt(), ((MARGIN + 2 * CELL + CELL / 2) * SCALE).toInt())
        assertThat(inside).isEqualTo(GREEN)
        bitmap.captureRoboImage("src/test/screenshots/ShapePainter_allKindsAndStyles.png")
    }

    private fun points(
        kind: ShapeKind,
        l: Float,
        t: Float,
        r: Float,
        b: Float,
    ): List<PointPt> =
        when (kind) {
            ShapeKind.LINE -> {
                listOf(PointPt(l, b), PointPt(r, t))
            }

            ShapeKind.ARROW -> {
                listOf(PointPt(l, b), PointPt(r, t))
            }

            ShapeKind.POLYGON -> {
                val cx = (l + r) / 2f
                listOf(
                    PointPt(cx, t),
                    PointPt(r, t + (b - t) * 0.4f),
                    PointPt(r - (r - l) * 0.2f, b),
                    PointPt(l + (r - l) * 0.2f, b),
                    PointPt(
                        l,
                        t + (b - t) * 0.4f,
                    ),
                )
            }

            else -> {
                ShapeGeometry.dragPoints(kind, PointPt(l, t), PointPt(r, b), constrain = false)!!
            }
        }

    private data class Style(
        val stroke: StrokeStyle,
        val fill: Int?,
        val rotationDeg: Float = 0f,
    )

    private companion object {
        const val SCALE = 1.5f
        const val MARGIN = 16f
        const val CELL = 90f
        const val GAP = 20f
        const val INK = 0xFF1A1A1A.toInt()
        const val BLUE = 0xFF2563EB.toInt()
        const val RED = 0xFFDC2626.toInt()
        const val GREEN = 0xFF16A34A.toInt()
        val KINDS = listOf(ShapeKind.LINE, ShapeKind.ARROW, ShapeKind.RECTANGLE, ShapeKind.ELLIPSE, ShapeKind.TRIANGLE, ShapeKind.POLYGON)
        val STYLES =
            listOf(
                Style(StrokeStyle(INK, 1.5f, dashed = false), null),
                Style(StrokeStyle(BLUE, 2.5f, dashed = true), null),
                Style(StrokeStyle(RED, 2f, dashed = false), 0x40DC2626),
                Style(StrokeStyle(INK, 2f, dashed = false), GREEN),
                Style(StrokeStyle(BLUE, 6f, dashed = false), null, rotationDeg = 20f),
            )
    }
}
