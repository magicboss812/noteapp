package dev.folio.core.format.codec

import dev.folio.core.model.AssetId
import dev.folio.core.model.Attachment
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.ImageObject
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageObject
import dev.folio.core.model.RectF01
import dev.folio.core.model.Shape
import dev.folio.core.model.StickyNote
import dev.folio.core.model.StrokeStyle
import dev.folio.core.format.proto.v1.Attachment as PbAttachment
import dev.folio.core.format.proto.v1.BrushSpec as PbBrushSpec
import dev.folio.core.format.proto.v1.FlowFrame as PbFlowFrame
import dev.folio.core.format.proto.v1.Image as PbImage
import dev.folio.core.format.proto.v1.InkStroke as PbInkStroke
import dev.folio.core.format.proto.v1.PageObject as PbPageObject
import dev.folio.core.format.proto.v1.Rect as PbRect
import dev.folio.core.format.proto.v1.Shape as PbShape
import dev.folio.core.format.proto.v1.StickyNote as PbStickyNote
import dev.folio.core.format.proto.v1.StrokeStyle as PbStrokeStyle

/** Page objects <-> proto. Unknown object kinds (all oneof members null) decode to null. */
internal object ObjectCodec {
    fun toProto(o: PageObject): PbPageObject {
        val id = o.id.value
        return when (o) {
            is InkStroke -> {
                PbPageObject(id = id, stroke = strokeToProto(o))
            }

            is Shape -> {
                PbPageObject(id = id, shape = shapeToProto(o))
            }

            is FlowFrame -> {
                PbPageObject(
                    id = id,
                    frame = PbFlowFrame(o.flow.value, rectToProto(o.rect), o.order, EnumCodec.frameRole(o.role), o.autoGrow),
                )
            }

            is ImageObject -> {
                val crop = PbRect(o.crop.left, o.crop.top, o.crop.right, o.crop.bottom)
                PbPageObject(id = id, image = PbImage(o.asset.value, rectToProto(o.rect), o.rotationDeg, crop))
            }

            is StickyNote -> {
                PbPageObject(id = id, sticky = PbStickyNote(o.flow.value, rectToProto(o.rect), o.rotationDeg, o.argb))
            }

            is Attachment -> {
                PbPageObject(id = id, attachment = PbAttachment(o.asset.value, o.displayName, o.mime, pointToProto(o.position)))
            }
        }
    }

    fun fromProto(pb: PbPageObject): PageObject? {
        corruptIf(pb.id.isBlank()) { "object id missing" }
        val id = ObjectId(pb.id)
        val stroke = pb.stroke
        val shape = pb.shape
        val frame = pb.frame
        val image = pb.image
        val sticky = pb.sticky
        val attachment = pb.attachment
        return when {
            stroke != null -> {
                strokeFromProto(id, stroke)
            }

            shape != null -> {
                shapeFromProto(id, shape)
            }

            frame != null -> {
                FlowFrame(
                    id,
                    FlowId(frame.flow_id),
                    rectFromProto(frame.rect, "frame rect"),
                    frame.order,
                    EnumCodec.frameRole(frame.role),
                    frame.auto_grow,
                )
            }

            image != null -> {
                imageFromProto(id, image)
            }

            sticky != null -> {
                StickyNote(id, FlowId(sticky.flow_id), rectFromProto(sticky.rect, "sticky rect"), sticky.rotation_deg, sticky.argb)
            }

            attachment != null -> {
                Attachment(
                    id,
                    AssetId(attachment.asset),
                    attachment.display_name,
                    attachment.mime,
                    pointFromProto(attachment.position.orCorrupt("attachment position")),
                )
            }

            else -> {
                null
            }
        }
    }

    private fun strokeToProto(o: InkStroke): PbInkStroke =
        PbInkStroke(
            brush = PbBrushSpec(EnumCodec.brushKind(o.brush.kind), o.brush.argb, o.brush.sizePt, o.brush.version, o.brush.pressureGamma),
            inputs = StrokeCodec.encode(o.inputs),
            bounds = rectToProto(o.bounds),
        )

    private fun strokeFromProto(
        id: ObjectId,
        pb: PbInkStroke,
    ): InkStroke {
        val b = pb.brush.orCorrupt("brush")
        val brush = BrushSpec(EnumCodec.brushKind(b.kind), b.argb, b.size_pt, b.version, b.pressure_gamma)
        return InkStroke(id, brush, StrokeCodec.decode(pb.inputs.orCorrupt("stroke inputs")), rectFromProto(pb.bounds, "stroke bounds"))
    }

    private fun shapeToProto(o: Shape): PbShape =
        PbShape(
            kind = EnumCodec.shapeKind(o.kind),
            points = o.points.map(::pointToProto),
            rotation_deg = o.rotationDeg,
            stroke = PbStrokeStyle(o.stroke.argb, o.stroke.widthPt, o.stroke.dashed),
            fill_argb = o.fillArgb ?: 0,
            has_fill = o.fillArgb != null,
            bounds = rectToProto(o.bounds),
        )

    private fun shapeFromProto(
        id: ObjectId,
        pb: PbShape,
    ): Shape {
        val style = pb.stroke.orCorrupt("shape style")
        return Shape(
            id = id,
            kind = EnumCodec.shapeKind(pb.kind),
            points = pb.points.map(::pointFromProto),
            rotationDeg = pb.rotation_deg,
            stroke = StrokeStyle(style.argb, style.width_pt, style.dashed),
            fillArgb = if (pb.has_fill) pb.fill_argb else null,
            bounds = rectFromProto(pb.bounds, "shape bounds"),
        )
    }

    private fun imageFromProto(
        id: ObjectId,
        pb: PbImage,
    ): ImageObject {
        val crop = pb.crop?.let { RectF01(it.left, it.top, it.right, it.bottom) } ?: RectF01.FULL
        return ImageObject(id, AssetId(pb.asset), rectFromProto(pb.rect, "image rect"), pb.rotation_deg, crop)
    }
}
