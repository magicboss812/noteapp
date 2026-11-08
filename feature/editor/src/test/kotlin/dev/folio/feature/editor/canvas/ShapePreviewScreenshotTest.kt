package dev.folio.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageObject
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.shape.ShapeGeometry
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h800dp-mdpi")
class ShapePreviewScreenshotTest {
    private val page = ModelFixtures.page("p0").toRef()
    private val viewport = Viewport(1f).apply { setPages(listOf(page), VIEW_W.toFloat(), VIEW_H.toFloat()) }
    private val preview = ShapePreview(ApplicationProvider.getApplicationContext(), viewport)
    private var objects: List<PageObject> = emptyList()
    private val input =
        ShapeInput(
            viewport,
            pageIdAt = { if (it == 0) page.id else null },
            objectsOn = { objects },
            spec = { ShapeSpec.DEFAULT },
            sizes = ShapeInputSizes(12f, 24f, 28f, 300L),
            invalidate = {},
            onHide = {},
            onCommit = { _, _, _ -> },
        ).also { preview.input = it }

    @Test
    fun screenshot_rotatedTriangleMidMove_boxHandlesRotateKnobAndMovedShape() {
        val triangle =
            ShapeGeometry.create(
                ObjectId("t"),
                ShapeKind.TRIANGLE,
                listOf(PointPt(200f, 150f), PointPt(300f, 300f), PointPt(100f, 300f)),
                StrokeStyle(0xFF2563EB.toInt(), 3f, dashed = false),
                0x402563EB,
            )
        objects = listOf(ShapeGeometry.withPoints(triangle, triangle.points, 20f))
        val center = viewport.toViewPx(page.id, PointPt(200f, 240f))
        val to = viewport.toViewPx(page.id, PointPt(230f, 270f))
        input.onStylusDown(event(ACTION_DOWN, 0, center.x to center.y, toolType = TOOL_TYPE_STYLUS), 0, eraser = false)
        input.onStylusMove(event(ACTION_MOVE, 10, to.x to to.y, toolType = TOOL_TYPE_STYLUS), 0)

        render().captureRoboImage("src/test/screenshots/ShapePreview_selectedMidMove.png")

        assertThat(preview.selectedShown?.id).isEqualTo(triangle.id)
        assertThat(input.editing).isTrue()
    }

    // The preview over white paper (it draws on a transparent overlay above the tiles).
    private fun render(): Bitmap {
        preview.measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
        )
        preview.layout(0, 0, VIEW_W, VIEW_H)
        val bitmap = Bitmap.createBitmap(VIEW_W, VIEW_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        preview.draw(canvas)
        return bitmap
    }

    private companion object {
        const val VIEW_W = 600
        const val VIEW_H = 800
    }
}
