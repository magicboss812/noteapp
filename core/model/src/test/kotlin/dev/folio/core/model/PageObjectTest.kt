package dev.folio.core.model

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.geometry.RectPt
import org.junit.Assert.assertThrows
import org.junit.Test

class PageObjectTest {
    @Test
    fun strokeInputs_equalContent_isEqual() {
        assertThat(inputs()).isEqualTo(inputs())
        assertThat(inputs().hashCode()).isEqualTo(inputs().hashCode())
        assertThat(inputs(pressure = null)).isNotEqualTo(inputs())
    }

    @Test
    fun strokeInputs_channelSizeMismatch_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            StrokeInputs(floatArrayOf(0f), floatArrayOf(0f, 1f), floatArrayOf(0f), null, null, null, InputTool.STYLUS)
        }
    }

    @Test
    fun inkStroke_of_boundsIncludeHalfBrushSize() {
        val brush = BrushSpec(BrushKind.BALLPOINT, 0xFF000000.toInt(), 2f, 1)
        val stroke = InkStroke.of(ObjectId("s"), brush, inputs())
        assertThat(stroke.bounds).isEqualTo(RectPt(-1f, -1f, 11f, 6f))
    }

    @Test
    fun image_rotated90_boundsSwapAroundCenter() {
        val img = ImageObject(ObjectId("i"), AssetId("a"), RectPt(0f, 0f, 20f, 10f), 90f, RectF01.FULL)
        assertThat(img.bounds.left).isWithin(1e-4f).of(5f)
        assertThat(img.bounds.top).isWithin(1e-4f).of(-5f)
        assertThat(img.bounds.right).isWithin(1e-4f).of(15f)
        assertThat(img.bounds.bottom).isWithin(1e-4f).of(15f)
    }

    @Test
    fun ids_random_areLowercaseUuid() {
        val id = DocId.random().value
        assertThat(id).matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
    }

    private fun inputs(pressure: FloatArray? = floatArrayOf(0.1f, 0.5f, 1f)) =
        StrokeInputs(
            x = floatArrayOf(0f, 5f, 10f),
            y = floatArrayOf(0f, 5f, 0f),
            tMs = floatArrayOf(0f, 4f, 8f),
            pressure = pressure,
            tiltDeg = null,
            orientationDeg = null,
            tool = InputTool.STYLUS,
        )
}
