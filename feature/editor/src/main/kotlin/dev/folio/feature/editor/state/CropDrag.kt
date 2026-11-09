package dev.folio.feature.editor.state

import dev.folio.core.model.RectF01
import dev.folio.core.model.image.ImageCrops
import kotlin.math.abs

/** What a drag on the crop screen moves: the whole crop, one edge or one corner. */
enum class CropHandle(
    internal val left: Boolean = false,
    internal val top: Boolean = false,
    internal val right: Boolean = false,
    internal val bottom: Boolean = false,
) {
    MOVE,
    LEFT(left = true),
    TOP(top = true),
    RIGHT(right = true),
    BOTTOM(bottom = true),
    TOP_LEFT(left = true, top = true),
    TOP_RIGHT(top = true, right = true),
    BOTTOM_LEFT(left = true, bottom = true),
    BOTTOM_RIGHT(right = true, bottom = true),
}

/**
 * Crop rectangle math of the crop screen (P07-T04), in the shown image's normalized space (0..1 each way): which handle
 * a touch takes and where a drag leaves the crop. Edges stop at the image and at [ImageCrops.MIN_CROP] from the
 * opposite edge; a move keeps the size and stays inside the image.
 */
object CropDrag {
    /** The handle at ([x], [y]) with grab distances [slopX], [slopY]; corners win over edges, null outside the crop. */
    fun handleAt(
        crop: RectF01,
        x: Float,
        y: Float,
        slopX: Float,
        slopY: Float,
    ): CropHandle? {
        val inX = x >= crop.left - slopX && x <= crop.right + slopX
        val inY = y >= crop.top - slopY && y <= crop.bottom + slopY
        if (!inX || !inY) return null
        val left = abs(x - crop.left) <= slopX
        val right = !left && abs(x - crop.right) <= slopX
        val top = abs(y - crop.top) <= slopY
        val bottom = !top && abs(y - crop.bottom) <= slopY
        return CropHandle.entries.firstOrNull {
            it != CropHandle.MOVE && it.left == left && it.right == right && it.top == top && it.bottom == bottom
        } ?: CropHandle.MOVE.takeIf { x in crop.left..crop.right && y in crop.top..crop.bottom }
    }

    /** [start] dragged by ([dx], [dy]) on [handle]. */
    fun dragged(
        start: RectF01,
        handle: CropHandle,
        dx: Float,
        dy: Float,
    ): RectF01 {
        if (handle == CropHandle.MOVE) {
            val mx = dx.coerceIn(-start.left, 1f - start.right)
            val my = dy.coerceIn(-start.top, 1f - start.bottom)
            return RectF01(start.left + mx, start.top + my, start.right + mx, start.bottom + my)
        }
        val min = ImageCrops.MIN_CROP
        return RectF01(
            if (handle.left) (start.left + dx).coerceIn(0f, start.right - min) else start.left,
            if (handle.top) (start.top + dy).coerceIn(0f, start.bottom - min) else start.top,
            if (handle.right) (start.right + dx).coerceIn(start.left + min, 1f) else start.right,
            if (handle.bottom) (start.bottom + dy).coerceIn(start.top + min, 1f) else start.bottom,
        )
    }
}
