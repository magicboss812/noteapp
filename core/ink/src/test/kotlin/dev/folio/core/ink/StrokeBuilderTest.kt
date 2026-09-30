package dev.folio.core.ink

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.StrokeInputs
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric only for android.* classes; the host libink.so comes from ink-nativeloader-jvm.
@RunWith(AndroidJUnit4::class)
class StrokeBuilderTest {
    @Test
    fun inputsOf_builtStroke_roundTripsEveryChannel() {
        val inputs =
            StrokeInputs(
                x = floatArrayOf(10f, 12.5f, 15f),
                y = floatArrayOf(20f, 21f, 23.25f),
                tMs = floatArrayOf(0f, 7f, 15f),
                pressure = floatArrayOf(0.2f, 0.6f, 1f),
                tiltDeg = floatArrayOf(10f, 30f, 45f),
                orientationDeg = floatArrayOf(0f, 90f, 180f),
                tool = InputTool.STYLUS,
            )
        val stroke = InkStroke.of(ObjectId("s"), BrushSpec(BrushKind.PENCIL, BLACK, 1f, 1), inputs)

        val back = StrokeBuilder.inputsOf(StrokeBuilder.build(stroke).inputs)

        assertThat(back.x).isEqualTo(inputs.x)
        assertThat(back.y).isEqualTo(inputs.y)
        assertThat(back.tMs).isEqualTo(inputs.tMs)
        assertThat(back.pressure).usingTolerance(1e-6).containsExactly(0.2f, 0.6f, 1f).inOrder()
        assertThat(back.tiltDeg).usingTolerance(1e-3).containsExactly(10f, 30f, 45f).inOrder()
        assertThat(back.orientationDeg).usingTolerance(1e-3).containsExactly(0f, 90f, 180f).inOrder()
        assertThat(back.tool).isEqualTo(InputTool.STYLUS)
    }

    @Test
    fun inputsOf_batchWithoutOptionalChannels_timesRelativeToFirstSample() {
        val batch =
            MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, x = 1f, y = 2f, elapsedTimeMillis = 100L)
                .add(InputToolType.STYLUS, x = 3f, y = 4f, elapsedTimeMillis = 108L)

        val inputs = StrokeBuilder.inputsOf(batch)

        assertThat(inputs.tMs).isEqualTo(floatArrayOf(0f, 8f))
        assertThat(inputs.pressure).isNull()
        assertThat(inputs.tiltDeg).isNull()
        assertThat(inputs.orientationDeg).isNull()
    }

    private companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}
