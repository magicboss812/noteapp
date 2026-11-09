package dev.folio.feature.editor.state

import dev.folio.core.model.AssetId
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.RectF01
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.image.ImageCrops

/**
 * An image ready to insert (P07-T04): stored [bytes] (JPEG or PNG, 04-file-format.md#assets) of [mime], its pixel size
 * and the quarter turn its EXIF orientation asks for.
 */
class ImportedImage(
    val bytes: ByteArray,
    val mime: String,
    val originalName: String?,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDeg: Float,
)

/** Image [id] of page [pageId], the one the crop and turn screen edits. */
data class ImageTarget(
    val pageId: PageId,
    val id: ObjectId,
)

/** The crop and turn screen's image: [target], its [asset], current [crop] of the source and [rotationDeg] on the page. */
data class ImageEditUi(
    val target: ImageTarget,
    val asset: AssetId,
    val crop: RectF01,
    val rotationDeg: Float,
)

/** What the crop screen hands back: the new [crop] of the source and the clockwise quarter [turns] to add. */
data class ImageCropResult(
    val crop: RectF01,
    val turns: Int,
)

/** Where a new image goes: centered in the [visible] part of the page, at most [FILL] of it each way. */
internal fun placeImage(
    image: ImportedImage,
    visible: RectPt,
): RectPt =
    ImageCrops.placement(
        image.widthPx,
        image.heightPx,
        image.rotationDeg,
        visible.center,
        visible.widthPt * FILL,
        visible.heightPt * FILL,
    )

private const val FILL = 0.6f
