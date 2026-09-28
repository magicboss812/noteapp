package dev.folio.core.model.edit

import dev.folio.core.model.Attachment
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.ImageObject
import dev.folio.core.model.InkStroke
import dev.folio.core.model.PageObject
import dev.folio.core.model.Shape
import dev.folio.core.model.StickyNote
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Object moved by [affine]. Strokes map every sample (brush size scales with sqrt|det|). Rotatable
 * objects (shapes, images, sticky notes) keep their local geometry: the center moves, width/height
 * scale by the affine's axis scales and the rotation angle is added; shear is dropped. Frames map to
 * the bounds of their mapped rect (they never rotate). Attachments move their anchor.
 */
fun PageObject.transformed(affine: Affine): PageObject =
    when (this) {
        is InkStroke -> {
            val scale = sqrt(abs(affine.determinant))
            InkStroke.of(id, brush.copy(sizePt = brush.sizePt * scale), inputs.transformed(affine))
        }

        is Shape -> {
            transformShape(this, affine)
        }

        is FlowFrame -> {
            copy(rect = affine.mapRect(rect))
        }

        is ImageObject -> {
            val (r, deg) = transformRotatedRect(rect, rotationDeg, affine)
            copy(rect = r, rotationDeg = deg)
        }

        is StickyNote -> {
            val (r, deg) = transformRotatedRect(rect, rotationDeg, affine)
            copy(rect = r, rotationDeg = deg)
        }

        is Attachment -> {
            copy(position = affine.mapPoint(position))
        }
    }

/** Object with its primary color set to [argb]; objects without a color are returned unchanged. */
fun PageObject.recolored(argb: Int): PageObject =
    when (this) {
        is InkStroke -> copy(brush = brush.copy(argb = argb))
        is Shape -> copy(stroke = stroke.copy(argb = argb))
        is StickyNote -> copy(argb = argb)
        is FlowFrame, is ImageObject, is Attachment -> this
    }

/** Bounds of a shape: its points' bounds rotated around their center, grown by half the outline width. */
fun shapeBounds(
    points: List<PointPt>,
    rotationDeg: Float,
    widthPt: Float,
): RectPt {
    val local = RectPt.bounds(points)
    if (local.isEmpty) return local
    val c = local.center
    val rotated = if (rotationDeg == 0f) local else Affine.rotate(rotationDeg, c.x, c.y).mapRect(local)
    return rotated.inset(-widthPt / 2f)
}

private fun axisScales(affine: Affine): Pair<Float, Float> = hypot(affine.a, affine.b) to hypot(affine.c, affine.d)

private fun rotationOf(affine: Affine): Float = Math.toDegrees(atan2(affine.b, affine.a).toDouble()).toFloat()

private fun transformShape(
    shape: Shape,
    affine: Affine,
): Shape {
    val local = RectPt.bounds(shape.points)
    if (local.isEmpty) return shape
    val c = local.center
    val newC = affine.mapPoint(c)
    val (sx, sy) = axisScales(affine)
    val points = shape.points.map { PointPt(newC.x + (it.x - c.x) * sx, newC.y + (it.y - c.y) * sy) }
    val deg = shape.rotationDeg + rotationOf(affine)
    val width = shape.stroke.widthPt * sqrt(abs(affine.determinant))
    return shape.copy(
        points = points,
        rotationDeg = deg,
        stroke = shape.stroke.copy(widthPt = width),
        bounds = shapeBounds(points, deg, width),
    )
}

private fun transformRotatedRect(
    rect: RectPt,
    rotationDeg: Float,
    affine: Affine,
): Pair<RectPt, Float> {
    val c = affine.mapPoint(rect.center)
    val (sx, sy) = axisScales(affine)
    val w = rect.widthPt * sx
    val h = rect.heightPt * sy
    return RectPt(c.x - w / 2f, c.y - h / 2f, c.x + w / 2f, c.y + h / 2f) to rotationDeg + rotationOf(affine)
}
