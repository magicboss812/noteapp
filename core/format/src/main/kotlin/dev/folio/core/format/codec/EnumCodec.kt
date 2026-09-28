package dev.folio.core.format.codec

import dev.folio.core.model.BrushKind
import dev.folio.core.model.FrameRole
import dev.folio.core.model.Orientation
import dev.folio.core.model.PaperSize
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.TemplateKind
import dev.folio.core.format.proto.v1.BrushKind as PbBrushKind
import dev.folio.core.format.proto.v1.FrameRole as PbFrameRole
import dev.folio.core.format.proto.v1.Orientation as PbOrientation
import dev.folio.core.format.proto.v1.PaperSize as PbPaperSize
import dev.folio.core.format.proto.v1.ShapeKind as PbShapeKind
import dev.folio.core.format.proto.v1.TemplateKind as PbTemplateKind

/**
 * Model <-> proto enums. Proto enums carry an UNSPECIFIED entry (and newer writers may add values
 * that decode to it); those map to a safe default so a document still opens.
 */
@Suppress("TooManyFunctions") // One to/from pair per enum; splitting would scatter the mapping table.
internal object EnumCodec {
    fun paperSize(v: PaperSize): PbPaperSize =
        when (v) {
            PaperSize.A3 -> PbPaperSize.A3
            PaperSize.A4 -> PbPaperSize.A4
            PaperSize.A5 -> PbPaperSize.A5
        }

    fun paperSize(v: PbPaperSize): PaperSize =
        when (v) {
            PbPaperSize.A3 -> PaperSize.A3
            PbPaperSize.A5 -> PaperSize.A5
            PbPaperSize.A4, PbPaperSize.PAPER_SIZE_UNSPECIFIED -> PaperSize.A4
        }

    fun orientation(v: Orientation): PbOrientation =
        when (v) {
            Orientation.PORTRAIT -> PbOrientation.PORTRAIT
            Orientation.LANDSCAPE -> PbOrientation.LANDSCAPE
        }

    fun orientation(v: PbOrientation): Orientation =
        when (v) {
            PbOrientation.LANDSCAPE -> Orientation.LANDSCAPE
            PbOrientation.PORTRAIT, PbOrientation.ORIENTATION_UNSPECIFIED -> Orientation.PORTRAIT
        }

    fun templateKind(v: TemplateKind): PbTemplateKind = PbTemplateKind.valueOf(v.name)

    fun templateKind(v: PbTemplateKind): TemplateKind =
        if (v == PbTemplateKind.TEMPLATE_KIND_UNSPECIFIED) TemplateKind.BLANK else TemplateKind.valueOf(v.name)

    fun brushKind(v: BrushKind): PbBrushKind = PbBrushKind.valueOf(v.name)

    fun brushKind(v: PbBrushKind): BrushKind =
        if (v == PbBrushKind.BRUSH_KIND_UNSPECIFIED) BrushKind.BALLPOINT else BrushKind.valueOf(v.name)

    fun shapeKind(v: ShapeKind): PbShapeKind = PbShapeKind.valueOf(v.name)

    fun shapeKind(v: PbShapeKind): ShapeKind = if (v == PbShapeKind.SHAPE_KIND_UNSPECIFIED) ShapeKind.POLYGON else ShapeKind.valueOf(v.name)

    fun frameRole(v: FrameRole): PbFrameRole = PbFrameRole.valueOf(v.name)

    fun frameRole(v: PbFrameRole): FrameRole = if (v == PbFrameRole.FRAME_ROLE_UNSPECIFIED) FrameRole.BOX else FrameRole.valueOf(v.name)
}
