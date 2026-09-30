package dev.folio.core.ink.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.StrokeBuilder
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.StrokeInputs
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/**
 * Every v1 brush draws the same synthetic stroke: a wave whose pressure ramps 0 -> 1 left to right
 * (top row at the medium preset width, bottom row at the large one). Real androidx.ink meshes: the
 * host libink.so loads from ink-nativeloader-jvm. Robolectric renders meshes as paths, which drop
 * per-vertex opacity (pencil pressure/tilt lightening); the goldens pin geometry, color and texture.
 */
@RunWith(AndroidJUnit4::class)
class BrushRampScreenshotTest {
    private val renderer = CanvasStrokeRenderer.create(BrushTextures)

    @Test
    fun screenshot_ballpoint() = capture(BrushKind.BALLPOINT)

    @Test
    fun screenshot_fountain() = capture(BrushKind.FOUNTAIN)

    @Test
    fun screenshot_pencil() = capture(BrushKind.PENCIL)

    @Test
    fun screenshot_marker() = capture(BrushKind.MARKER)

    @Test
    fun screenshot_highlighter() = capture(BrushKind.HIGHLIGHTER)

    /** Pencil with a tilt ramp 0 -> 69 deg instead of pressure changes (side shading widens and lightens). */
    @Test
    fun screenshot_pencilTilt() {
        val bitmap = render(BrushKind.PENCIL, tilt = true)
        bitmap.captureRoboImage("src/test/screenshots/BrushRamp_PENCIL_tilt_v1.png")
    }

    @Test
    fun pencil_constantPressureLine_grainVariesAlongTheLine_markerDoesNot() {
        fun centerline(kind: BrushKind): Set<Int> {
            val bitmap = Bitmap.createBitmap(px(WIDTH_PT), px(ROW_PT), Bitmap.Config.ARGB_8888)
            val toDevice = Matrix().apply { setScale(SCALE, SCALE) }
            val canvas = Canvas(bitmap).apply { setMatrix(toDevice) }
            val n = 2
            val xs = floatArrayOf(MARGIN_PT, WIDTH_PT - MARGIN_PT)
            val ys = FloatArray(n) { ROW_PT / 2 }
            val inputs = StrokeInputs(xs, ys, floatArrayOf(0f, DURATION_MS), FloatArray(n) { 1f }, null, null, InputTool.SYNTHETIC)
            val spec = BrushSpec(kind, BrushPresets.PEN_PALETTE[0], 4f, 1)
            renderer.draw(canvas, StrokeBuilder.build(InkStroke(ObjectId("line"), spec, inputs, inputs.pointBounds())), toDevice)
            val y = px(ROW_PT / 2)
            return (px(2 * MARGIN_PT) until px(WIDTH_PT - 2 * MARGIN_PT)).map { bitmap.getPixel(it, y) }.toSet()
        }

        assertThat(centerline(BrushKind.MARKER)).hasSize(1)
        assertThat(centerline(BrushKind.PENCIL).size).isGreaterThan(10)
    }

    private fun capture(kind: BrushKind) {
        render(kind, tilt = false).captureRoboImage("src/test/screenshots/BrushRamp_${kind.name}_v1.png")
    }

    private fun render(
        kind: BrushKind,
        tilt: Boolean,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(px(WIDTH_PT), px(HEIGHT_PT), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val toDevice = Matrix().apply { setScale(SCALE, SCALE) }
        canvas.setMatrix(toDevice)
        val widths = BrushPresets.widthsPt(kind)
        listOf(widths[1], widths[2]).forEachIndexed { row, width ->
            val spec = BrushSpec(kind, BrushPresets.palette(kind)[0], width, 1)
            renderer.draw(canvas, StrokeBuilder.build(stroke(spec, row, tilt)), toDevice)
        }
        return bitmap
    }

    private fun stroke(
        spec: BrushSpec,
        row: Int,
        tilt: Boolean,
    ): InkStroke {
        val x = FloatArray(SAMPLES) { MARGIN_PT + it * (WIDTH_PT - 2 * MARGIN_PT) / (SAMPLES - 1) }
        val y = FloatArray(SAMPLES) { rowCenterPt(row) + AMPLITUDE_PT * sin(it * WAVES * 2 * PI / (SAMPLES - 1)).toFloat() }
        val t = FloatArray(SAMPLES) { it * DURATION_MS / (SAMPLES - 1) }
        val ramp = FloatArray(SAMPLES) { it / (SAMPLES - 1f) }
        val inputs =
            if (tilt) {
                StrokeInputs(
                    x,
                    y,
                    t,
                    FloatArray(SAMPLES) { 0.6f },
                    FloatArray(SAMPLES) { ramp[it] * MAX_TILT_DEG },
                    null,
                    InputTool.SYNTHETIC,
                )
            } else {
                StrokeInputs(x, y, t, ramp, null, null, InputTool.SYNTHETIC)
            }
        return InkStroke(ObjectId("ramp$row"), spec, inputs, inputs.pointBounds())
    }

    private fun rowCenterPt(row: Int): Float = ROW_PT / 2 + row * ROW_PT

    private fun px(pt: Float): Int = (pt * SCALE).toInt()

    private companion object {
        const val SCALE = 4f
        const val WIDTH_PT = 200f
        const val ROW_PT = 50f
        const val HEIGHT_PT = 2 * ROW_PT
        const val MARGIN_PT = 12f
        const val AMPLITUDE_PT = 10f
        const val WAVES = 3
        const val SAMPLES = 150
        const val DURATION_MS = 1200f
        const val MAX_TILT_DEG = 69f
    }
}
