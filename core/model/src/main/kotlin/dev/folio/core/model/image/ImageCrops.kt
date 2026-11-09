package dev.folio.core.model.image

import dev.folio.core.model.ImageObject
import dev.folio.core.model.RectF01
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Geometry of placed images (03-document-model.md#objects, P07-T04): where a new image goes, crops that keep the shown
 * content in place, quarter turns, and the mapping between the source image and its on-page (turned) orientation that
 * the crop screen works in. Crops are normalized 0..1 of the source image, rotation is around the rect center.
 */
object ImageCrops {
    /** Smallest crop side as a fraction of the source image. */
    const val MIN_CROP = 0.05f

    private const val QUARTER_DEG = 90f
    private const val FULL_TURN_DEG = 360f
    private const val TURNS = 4

    /**
     * The rect of a new [widthPx] x [heightPx] image turned by [rotationDeg] (a quarter turn from EXIF): aspect kept,
     * its turned extent at most [maxWidthPt] x [maxHeightPt] (never upscaled past 1 px = 1 pt), centered on [center].
     */
    fun placement(
        widthPx: Int,
        heightPx: Int,
        rotationDeg: Float,
        center: PointPt,
        maxWidthPt: Float,
        maxHeightPt: Float,
    ): RectPt {
        require(widthPx > 0 && heightPx > 0) { "empty image" }
        val sideways = quarterTurns(rotationDeg) % 2 == 1
        val shownW = if (sideways) heightPx else widthPx
        val shownH = if (sideways) widthPx else heightPx
        val scale = min(1f, min(maxWidthPt / shownW, maxHeightPt / shownH))
        val w = widthPx * scale
        val h = heightPx * scale
        return RectPt(center.x - w / 2f, center.y - h / 2f, center.x + w / 2f, center.y + h / 2f)
    }

    /** [crop] inside 0..1 with ordered edges and each side at least [MIN_CROP]. */
    fun clamp(crop: RectF01): RectF01 {
        var l = min(crop.left, crop.right).coerceIn(0f, 1f - MIN_CROP)
        var t = min(crop.top, crop.bottom).coerceIn(0f, 1f - MIN_CROP)
        var r = maxOf(crop.left, crop.right).coerceIn(MIN_CROP, 1f)
        var b = maxOf(crop.top, crop.bottom).coerceIn(MIN_CROP, 1f)
        if (r - l < MIN_CROP) {
            if (l + MIN_CROP <= 1f) {
                r = l + MIN_CROP
            } else {
                l = r - MIN_CROP
            }
        }
        if (b - t < MIN_CROP) {
            if (t + MIN_CROP <= 1f) {
                b = t + MIN_CROP
            } else {
                t = b - MIN_CROP
            }
        }
        return RectF01(l, t, r, b)
    }

    /**
     * [image] showing [newCrop] (clamped) at the same scale: the part of the picture both crops show stays where it is on
     * the page, also for a rotated image.
     */
    fun withCrop(
        image: ImageObject,
        newCrop: RectF01,
    ): ImageObject {
        val crop = clamp(newCrop)
        val old = image.crop
        val r = image.rect
        val sx = r.widthPt / (old.right - old.left)
        val sy = r.heightPt / (old.bottom - old.top)
        // The new rect in the image's own (unrotated) frame, laid around the old center.
        val local =
            RectPt(
                r.left + (crop.left - old.left) * sx,
                r.top + (crop.top - old.top) * sy,
                r.left + (crop.right - old.left) * sx,
                r.top + (crop.bottom - old.top) * sy,
            )
        val c = r.center
        val center = if (image.rotationDeg == 0f) local.center else Affine.rotate(image.rotationDeg, c.x, c.y).mapPoint(local.center)
        val w = local.widthPt / 2f
        val h = local.heightPt / 2f
        return image.copy(rect = RectPt(center.x - w, center.y - h, center.x + w, center.y + h), crop = crop)
    }

    /** [image] turned by [quarters] quarter turns (positive: clockwise on the page) around its center; angle in [0, 360). */
    fun turned(
        image: ImageObject,
        quarters: Int,
    ): ImageObject = image.copy(rotationDeg = normalized(image.rotationDeg + quarters * QUARTER_DEG))

    /** The quarter turn nearest [rotationDeg], 0..3 clockwise. */
    fun quarterTurns(rotationDeg: Float): Int = Math.floorMod((normalized(rotationDeg) / QUARTER_DEG).roundToInt(), TURNS)

    /** Source-normalized [crop] as seen after [quarters] clockwise quarter turns (what the crop screen shows). */
    fun toShown(
        crop: RectF01,
        quarters: Int,
    ): RectF01 =
        when (Math.floorMod(quarters, TURNS)) {
            // A clockwise turn maps source (u, v) to shown (1 - v, u).
            1 -> RectF01(1f - crop.bottom, crop.left, 1f - crop.top, crop.right)

            2 -> RectF01(1f - crop.right, 1f - crop.bottom, 1f - crop.left, 1f - crop.top)

            TURNS - 1 -> RectF01(crop.top, 1f - crop.right, crop.bottom, 1f - crop.left)

            else -> crop
        }

    /** Inverse of [toShown]. */
    fun toSource(
        shown: RectF01,
        quarters: Int,
    ): RectF01 = toShown(shown, TURNS - Math.floorMod(quarters, TURNS))

    /** Source pixel rect of [crop] in a [widthPx] x [heightPx] image as left, top, right, bottom (at least 1 px). */
    fun sourcePx(
        crop: RectF01,
        widthPx: Int,
        heightPx: Int,
    ): IntArray {
        val l = (crop.left * widthPx).roundToInt().coerceIn(0, widthPx - 1)
        val t = (crop.top * heightPx).roundToInt().coerceIn(0, heightPx - 1)
        val r = (crop.right * widthPx).roundToInt().coerceIn(l + 1, widthPx)
        val b = (crop.bottom * heightPx).roundToInt().coerceIn(t + 1, heightPx)
        return intArrayOf(l, t, r, b)
    }

    private fun normalized(deg: Float): Float {
        val d = deg % FULL_TURN_DEG
        return if (d < 0f) d + FULL_TURN_DEG else d
    }
}
