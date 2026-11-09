package dev.folio.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.edit.SelectionEdit
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.shape.ShapeGeometry
import dev.folio.core.render.PageContent
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.RenderTarget
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w600dp-h800dp-mdpi")
class LassoOverlayScreenshotTest {
    // Six handwriting-like waves at 80..280 pt, a rectangle below them, one wave outside every lasso.
    private val waves = List(6) { i -> wave("w$i", 80f, 100f + i * 40f) }
    private val shape =
        ShapeGeometry.create(
            ObjectId("r"),
            ShapeKind.RECTANGLE,
            listOf(PointPt(100f, 360f), PointPt(240f, 420f)),
            StrokeStyle(0xFF2563EB.toInt(), 3f, dashed = false),
            null,
        )
    private val outside = wave("out", 360f, 600f)
    private val body = ModelFixtures.page("p0", waves + shape + outside)
    private val ref = body.toRef()
    private val viewport = Viewport(1f).apply { setPages(listOf(ref), VIEW_W.toFloat(), VIEW_H.toFloat()) }
    private val overlay = LassoOverlay(ApplicationProvider.getApplicationContext(), viewport)
    private val renderer = PageRenderer()
    private var hidden: Set<ObjectId> = emptySet()

    private val host =
        object : LassoHost {
            override fun invalidate() = Unit

            override fun hide(selection: LassoSelection?) {
                hidden = selection?.ids?.toSet().orEmpty()
            }

            override fun snapshot(
                selection: LassoSelection,
                box: RectPt,
            ): LassoSnapshot {
                val region = box.inset(-2f)
                val bitmap = Bitmap.createBitmap(region.widthPt.toInt() + 1, region.heightPt.toInt() + 1, Bitmap.Config.ARGB_8888)
                val ids = selection.ids.toSet()
                val page = body.copy(objects = body.objects.removeAll { it.id !in ids })
                renderer.draw(Canvas(bitmap), ref, PageContent.of(page), region, 1f, RenderTarget.SCREEN_CONTENT)
                return LassoSnapshot(bitmap, region, 1f)
            }

            override fun edit(
                selection: LassoSelection,
                edit: SelectionEdit,
                done: (Boolean) -> Unit,
            ) = done(true)

            override fun isDrawn(
                pageId: PageId,
                rect: RectPt,
            ) = false
        }

    private val input =
        LassoInput(
            viewport,
            pageIdAt = { if (it == 0) ref.id else null },
            indexOf = { if (it == ref.id) 0 else -1 },
            objectsOn = { body.objects },
            spec = { LassoSpec.DEFAULT },
            clipboard = { ObjectClipboard() },
            sizes = LassoSizes(24f, 28f, 8f, 8f),
            host = host,
            toolbar = LassoToolbar(1f),
        ).also { overlay.input = it }

    @Test
    fun screenshot_selection_boxHandlesActionBarAndColorPopup() {
        lasso(PointPt(60f, 70f), PointPt(320f, 60f), PointPt(330f, 450f), PointPt(50f, 440f))
        render() // places the action bar
        val bar = input.toolbar.pill
        var x = bar.left
        while (input.toolbar.buttonAt(x, bar.centerY()) != LassoButton.COLOR) x += 1f
        tapPx(x + 10f, bar.centerY())

        render().captureRoboImage("src/test/screenshots/LassoOverlay_selectionWithColorPopup.png")

        assertThat(input.selection?.ids).hasSize(7)
        assertThat(input.toolbar.popupKind).isEqualTo(LassoPopup.COLOR)
        assertThat(overlay.boxShown).isNotNull()
    }

    @Test
    fun screenshot_rotatingTheSelection_snapshotTurnsWithTheAngleLabel() {
        lasso(PointPt(60f, 70f), PointPt(320f, 60f), PointPt(330f, 330f), PointPt(50f, 320f))
        val box = checkNotNull(input.box())
        val c = box.center
        val knob = viewport.toViewPx(ref.id, PointPt(c.x, box.top - input.rotateOffsetPt()))
        val to = viewport.toViewPx(ref.id, PointPt(c.x + 160f, c.y - 100f))
        input.onStylusDown(stylus(ACTION_DOWN, 0, knob.x to knob.y), 0, eraser = false)
        input.onStylusMove(stylus(ACTION_MOVE, 10, to.x to to.y), 0)

        render().captureRoboImage("src/test/screenshots/LassoOverlay_rotating.png")

        assertThat(input.rotationDeg()).isNotNull()
        assertThat(hidden).hasSize(6)
        input.onStylusUp(stylus(ACTION_UP, 20, to.x to to.y), 0)
        assertThat(input.pending).isNotNull()
    }

    @Test
    fun screenshot_drawingAFreeformLasso_dashedPath() {
        val points = List(9) { i -> PointPt(70f + i * 30f, if (i % 2 == 0) 80f else 60f) } + PointPt(330f, 300f) + PointPt(60f, 310f)
        val px = points.map { viewport.toViewPx(ref.id, it) }
        input.onStylusDown(stylus(ACTION_DOWN, 0, px[0].x to px[0].y), 0, eraser = false)
        for (i in 1 until px.size) input.onStylusMove(stylus(ACTION_MOVE, i * 10L, px[i].x to px[i].y), 0)

        render().captureRoboImage("src/test/screenshots/LassoOverlay_drawingPath.png")

        assertThat(input.pathSize).isEqualTo(points.size)
        assertThat(overlay.boxShown).isNull()
    }

    @Test
    fun actionBar_tallSelection_staysAboveTheChromeCoveringTheBottom() {
        overlay.coveredBottomPx = COVERED_PX
        lasso(PointPt(20f, 20f), PointPt(580f, 20f), PointPt(580f, 900f), PointPt(20f, 900f))

        render()

        val bar = input.toolbar.pill
        assertThat(input.selection?.ids).hasSize(8)
        assertThat(bar.isEmpty).isFalse()
        assertThat(bar.bottom).isAtMost((VIEW_H - COVERED_PX).toFloat())
    }

    // The page as the tiles show it (without the hidden objects), then the overlay on top.
    private fun render(): Bitmap {
        overlay.measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
        )
        overlay.layout(0, 0, VIEW_W, VIEW_H)
        val bitmap = Bitmap.createBitmap(VIEW_W, VIEW_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val shown = body.copy(objects = body.objects.removeAll { it.id in hidden })
        val whole = RectPt(0f, 0f, ref.spec.widthPt, ref.spec.heightPt)
        canvas.save()
        canvas.translate(viewport.pageOriginViewX(0).toFloat(), viewport.pageOriginViewY(0).toFloat())
        renderer.draw(canvas, ref, PageContent.of(shown), whole, viewport.scale, RenderTarget.SCREEN_CONTENT)
        canvas.restore()
        overlay.draw(canvas)
        return bitmap
    }

    private fun lasso(vararg points: PointPt) {
        val px = points.map { viewport.toViewPx(ref.id, it) }
        input.onStylusDown(stylus(ACTION_DOWN, 0, px[0].x to px[0].y), 0, eraser = false)
        for (i in 1 until px.size) input.onStylusMove(stylus(ACTION_MOVE, i * 10L, px[i].x to px[i].y), 0)
        input.onStylusUp(stylus(ACTION_UP, px.size * 10L, px.last().x to px.last().y), 0)
    }

    private fun tapPx(
        x: Float,
        y: Float,
    ) {
        input.onStylusDown(stylus(ACTION_DOWN, 0, x to y), 0, eraser = false)
        input.onStylusUp(stylus(ACTION_UP, 20, x to y), 0)
    }

    private fun stylus(
        action: Int,
        timeMs: Long,
        px: Pair<Float, Float>,
    ) = event(action, timeMs, px, toolType = TOOL_TYPE_STYLUS)

    private companion object {
        const val VIEW_W = 600
        const val VIEW_H = 800
        const val WAVE_POINTS = 40
        const val COVERED_PX = 200

        // A 200 x 16 pt sine wave starting at ([x], [y]).
        fun wave(
            id: String,
            x: Float,
            y: Float,
        ): InkStroke {
            val xs = FloatArray(WAVE_POINTS) { x + it * 200f / (WAVE_POINTS - 1) }
            val ys = FloatArray(WAVE_POINTS) { y + 8f * sin(it * 0.5f) }
            val ts = FloatArray(WAVE_POINTS) { it * 8f }
            val inputs = StrokeInputs(xs, ys, ts, null, null, null, InputTool.SYNTHETIC)
            return InkStroke.of(ObjectId(id), BrushSpec(BrushKind.BALLPOINT, 0xFF1A1A1A.toInt(), 1.5f, 1), inputs)
        }
    }
}
