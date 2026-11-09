package dev.folio.core.model.image

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.AssetId
import dev.folio.core.model.ImageObject
import dev.folio.core.model.ObjectId
import dev.folio.core.model.RectF01
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import org.junit.Test
import kotlin.random.Random

class ImageCropsTest {
    private fun image(
        rect: RectPt = RectPt(0f, 0f, 100f, 50f),
        rotationDeg: Float = 0f,
        crop: RectF01 = RectF01.FULL,
    ) = ImageObject(ObjectId("i"), AssetId("a".repeat(64)), rect, rotationDeg, crop)

    // Where source point (u, v) of [image] lies on the page.
    private fun pageOf(
        image: ImageObject,
        u: Float,
        v: Float,
    ): PointPt {
        val r = image.rect
        val c = image.crop
        val local = PointPt(r.left + (u - c.left) / (c.right - c.left) * r.widthPt, r.top + (v - c.top) / (c.bottom - c.top) * r.heightPt)
        return Affine.rotate(image.rotationDeg, r.center.x, r.center.y).mapPoint(local)
    }

    private fun assertNear(
        actual: PointPt,
        expected: PointPt,
    ) {
        assertThat(actual.x).isWithin(1e-3f).of(expected.x)
        assertThat(actual.y).isWithin(1e-3f).of(expected.y)
    }

    private fun assertNear(
        actual: RectPt,
        expected: RectPt,
    ) {
        assertNear(PointPt(actual.left, actual.top), PointPt(expected.left, expected.top))
        assertNear(PointPt(actual.right, actual.bottom), PointPt(expected.right, expected.bottom))
    }

    @Test
    fun withCrop_rightHalf_keepsContentInPlaceAndScale() {
        val cropped = ImageCrops.withCrop(image(), RectF01(0.5f, 0f, 1f, 1f))

        assertNear(cropped.rect, RectPt(50f, 0f, 100f, 50f))
        assertThat(cropped.crop).isEqualTo(RectF01(0.5f, 0f, 1f, 1f))
    }

    @Test
    fun withCrop_rotated90_rightHalfStaysWhereItWas() {
        val img = image(rotationDeg = 90f)
        val cropped = ImageCrops.withCrop(img, RectF01(0.5f, 0f, 1f, 1f))

        assertNear(cropped.rect, RectPt(25f, 25f, 75f, 75f))
        assertNear(pageOf(cropped, 1f, 0f), pageOf(img, 1f, 0f))
        assertNear(pageOf(cropped, 0.5f, 1f), pageOf(img, 0.5f, 1f))
    }

    @Test
    fun withCrop_anyCropOfAnyRotation_sharedContentKeepsItsPagePosition() {
        val random = Random(404)
        repeat(200) {
            val rect =
                RectPt.ofSize(
                    random.nextFloat() * 300f,
                    random.nextFloat() * 300f,
                    20f + random.nextFloat() * 200f,
                    20f + random.nextFloat() * 200f,
                )
            val start =
                ImageCrops.clamp(
                    RectF01(
                        random.nextFloat() * 0.4f,
                        random.nextFloat() * 0.4f,
                        0.6f + random.nextFloat() * 0.4f,
                        0.6f + random.nextFloat() * 0.4f,
                    ),
                )
            val img = image(rect, random.nextFloat() * 360f, start)
            val next = RectF01(0.45f, 0.45f, 0.55f + random.nextFloat() * 0.45f, 0.55f + random.nextFloat() * 0.45f)

            val cropped = ImageCrops.withCrop(img, next)

            assertNear(pageOf(cropped, 0.5f, 0.5f), pageOf(img, 0.5f, 0.5f))
            assertNear(pageOf(cropped, next.right, next.bottom), pageOf(img, next.right, next.bottom))
        }
    }

    @Test
    fun withCrop_backToFull_restoresTheOriginalRect() {
        val img = image(rotationDeg = 33f)
        val back = ImageCrops.withCrop(ImageCrops.withCrop(img, RectF01(0.1f, 0.2f, 0.7f, 0.9f)), RectF01.FULL)

        assertNear(back.rect, img.rect)
        assertThat(back.crop).isEqualTo(RectF01.FULL)
    }

    @Test
    fun clamp_outOfRangeInvertedAndTiny_isValid() {
        assertThat(ImageCrops.clamp(RectF01(-0.2f, 1.3f, 0.6f, 0.4f))).isEqualTo(RectF01(0f, 0.4f, 0.6f, 1f))
        val tiny = ImageCrops.clamp(RectF01(0.99f, 0.5f, 0.995f, 0.5f))
        assertThat(tiny.right - tiny.left).isWithin(1e-6f).of(ImageCrops.MIN_CROP)
        assertThat(tiny.right).isAtMost(1f)
        assertThat(tiny.bottom - tiny.top).isWithin(1e-6f).of(ImageCrops.MIN_CROP)
    }

    @Test
    fun toShown_quarterTurns_roundTripAndMatchTurn() {
        val crop = RectF01(0.1f, 0.2f, 0.4f, 0.9f)
        for (q in -4..7) assertNear(ImageCrops.toSource(ImageCrops.toShown(crop, q), q), crop)
        // A clockwise turn brings the left strip of the source to the top.
        assertNear(ImageCrops.toShown(RectF01(0f, 0f, 0.25f, 1f), 1), RectF01(0f, 0f, 1f, 0.25f))
        assertNear(ImageCrops.toShown(crop, 2), RectF01(0.6f, 0.1f, 0.9f, 0.8f))
    }

    private fun assertNear(
        actual: RectF01,
        expected: RectF01,
    ) = assertNear(
        RectPt(actual.left, actual.top, actual.right, actual.bottom),
        RectPt(expected.left, expected.top, expected.right, expected.bottom),
    )

    @Test
    fun turned_andQuarterTurns_normalizeAngle() {
        assertThat(ImageCrops.turned(image(rotationDeg = 300f), 1).rotationDeg).isEqualTo(30f)
        assertThat(ImageCrops.turned(image(), -1).rotationDeg).isEqualTo(270f)
        assertThat(ImageCrops.quarterTurns(-90f)).isEqualTo(3)
        assertThat(ImageCrops.quarterTurns(359f)).isEqualTo(0)
        assertThat(ImageCrops.quarterTurns(100f)).isEqualTo(1)
    }

    @Test
    fun placement_landscapePhotoTurnedSideways_fitsItsShownExtent() {
        val r = ImageCrops.placement(4000, 3000, 90f, PointPt(300f, 400f), maxWidthPt = 300f, maxHeightPt = 300f)

        // Shown 3000 x 4000 fits 300 tall: scale 0.075, so the unrotated rect is 300 x 225.
        assertNear(r, RectPt(150f, 287.5f, 450f, 512.5f))
    }

    @Test
    fun placement_smallImage_isNotUpscaled() {
        val r = ImageCrops.placement(40, 20, 0f, PointPt(100f, 100f), maxWidthPt = 300f, maxHeightPt = 300f)

        assertNear(r, RectPt(80f, 90f, 120f, 110f))
    }

    @Test
    fun sourcePx_cropOfImage_roundsAndKeepsOnePixel() {
        assertThat(ImageCrops.sourcePx(RectF01(0.25f, 0f, 0.75f, 0.5f), 200, 100).toList()).containsExactly(50, 0, 150, 50).inOrder()
        assertThat(ImageCrops.sourcePx(RectF01(1f, 1f, 1f, 1f), 10, 10).toList()).containsExactly(9, 9, 10, 10).inOrder()
    }
}
