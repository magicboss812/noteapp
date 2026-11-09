package dev.folio.feature.editor.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.RectF01
import dev.folio.core.model.image.ImageCrops
import org.junit.Test

class CropDragTest {
    private val crop = RectF01(0.2f, 0.2f, 0.8f, 0.6f)

    @Test
    fun handleAt_cornersEdgesInsideOutside() {
        assertThat(CropDrag.handleAt(crop, 0.21f, 0.19f, 0.03f, 0.03f)).isEqualTo(CropHandle.TOP_LEFT)
        assertThat(CropDrag.handleAt(crop, 0.8f, 0.6f, 0.03f, 0.03f)).isEqualTo(CropHandle.BOTTOM_RIGHT)
        assertThat(CropDrag.handleAt(crop, 0.5f, 0.21f, 0.03f, 0.03f)).isEqualTo(CropHandle.TOP)
        assertThat(CropDrag.handleAt(crop, 0.79f, 0.4f, 0.03f, 0.03f)).isEqualTo(CropHandle.RIGHT)
        assertThat(CropDrag.handleAt(crop, 0.5f, 0.4f, 0.03f, 0.03f)).isEqualTo(CropHandle.MOVE)
        assertThat(CropDrag.handleAt(crop, 0.1f, 0.4f, 0.03f, 0.03f)).isNull()
    }

    @Test
    fun dragged_moveKeepsSizeInsideImage() {
        val moved = CropDrag.dragged(crop, CropHandle.MOVE, 0.5f, -0.5f)

        assertThat(moved.left).isWithin(1e-6f).of(0.4f)
        assertThat(moved.right).isWithin(1e-6f).of(1f)
        assertThat(moved.top).isWithin(1e-6f).of(0f)
        assertThat(moved.bottom).isWithin(1e-6f).of(0.4f)
    }

    @Test
    fun dragged_cornerStopsAtImageAndMinimumSize() {
        val out = CropDrag.dragged(crop, CropHandle.TOP_LEFT, -1f, -1f)
        assertThat(out).isEqualTo(RectF01(0f, 0f, 0.8f, 0.6f))

        val tiny = CropDrag.dragged(crop, CropHandle.BOTTOM_RIGHT, -1f, -1f)
        assertThat(tiny.right - tiny.left).isWithin(1e-6f).of(ImageCrops.MIN_CROP)
        assertThat(tiny.bottom - tiny.top).isWithin(1e-6f).of(ImageCrops.MIN_CROP)
        assertThat(tiny.left).isEqualTo(crop.left)
    }

    @Test
    fun dragged_edgeMovesOnlyItsSide() {
        val r = CropDrag.dragged(crop, CropHandle.RIGHT, -0.1f, 0.3f)

        assertThat(r.right).isWithin(1e-6f).of(0.7f)
        assertThat(r.copy(right = crop.right)).isEqualTo(crop)
    }
}
