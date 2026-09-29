package dev.folio.core.format.codec

import dev.folio.core.model.AssetId
import dev.folio.core.model.Background
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PdfBackground
import dev.folio.core.model.Template
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.format.proto.v1.Background as PbBackground
import dev.folio.core.format.proto.v1.Custom as PbCustom
import dev.folio.core.format.proto.v1.Fixed as PbFixed
import dev.folio.core.format.proto.v1.Infinite as PbInfinite
import dev.folio.core.format.proto.v1.PageSpec as PbPageSpec
import dev.folio.core.format.proto.v1.PdfBackground as PbPdfBackground
import dev.folio.core.format.proto.v1.Point as PbPoint
import dev.folio.core.format.proto.v1.Rect as PbRect
import dev.folio.core.format.proto.v1.Template as PbTemplate

/** Page spec and background <-> proto. */
internal object LayoutCodec {
    fun specToProto(spec: PageSpec): PbPageSpec =
        when (spec) {
            is PageSpec.Fixed -> {
                PbPageSpec(fixed = fixedToProto(spec))
            }

            is PageSpec.Custom -> {
                PbPageSpec(custom = PbCustom(spec.widthPt, spec.heightPt))
            }

            is PageSpec.Infinite -> {
                when (val origin = spec.origin) {
                    is PageSpec.Fixed -> PbPageSpec(infinite = PbInfinite(origin_fixed = fixedToProto(origin)))
                    is PageSpec.Custom -> PbPageSpec(infinite = PbInfinite(origin_custom = PbCustom(origin.widthPt, origin.heightPt)))
                    is PageSpec.Infinite -> error("nested infinite spec")
                }
            }
        }

    fun specFromProto(pb: PbPageSpec): PageSpec {
        val fixed = pb.fixed
        val custom = pb.custom
        val infinite = pb.infinite
        return when {
            fixed != null -> fixedFromProto(fixed)
            custom != null -> customFromProto(custom)
            infinite != null -> PageSpec.Infinite(originFromProto(infinite))
            else -> throw CorruptDataException("page spec has no kind")
        }
    }

    fun backgroundToProto(bg: Background): PbBackground =
        PbBackground(
            paper_argb = bg.paperArgb,
            template =
                PbTemplate(
                    kind = EnumCodec.templateKind(bg.template.kind),
                    spacing_pt = bg.template.spacingPt,
                    line_argb = bg.template.lineArgb,
                    margin_left_pt = bg.template.marginLeftPt,
                    margin_top_pt = bg.template.marginTopPt,
                    custom_asset =
                        bg.template.customAsset
                            ?.value
                            .orEmpty(),
                    custom_grid_pt = bg.template.customGridPt ?: 0f,
                ),
            pdf = bg.pdf?.let { PbPdfBackground(it.asset.value, it.pageIndex) },
        )

    fun backgroundFromProto(pb: PbBackground): Background {
        val t = pb.template.orCorrupt("template")
        val template =
            Template(
                kind = EnumCodec.templateKind(t.kind),
                spacingPt = t.spacing_pt,
                lineArgb = t.line_argb,
                marginLeftPt = t.margin_left_pt,
                marginTopPt = t.margin_top_pt,
                customAsset = t.custom_asset.takeIf { it.isNotEmpty() }?.let(::AssetId),
                customGridPt = t.custom_grid_pt.takeIf { it != 0f },
            )
        val pdf =
            pb.pdf?.let {
                corruptIf(it.asset.isEmpty() || it.page_index < 0) { "pdf background invalid" }
                PdfBackground(AssetId(it.asset), it.page_index)
            }
        return Background(pb.paper_argb, template, pdf)
    }

    private fun originFromProto(pb: PbInfinite): PageSpec {
        val fixed = pb.origin_fixed
        val custom = pb.origin_custom
        return when {
            fixed != null -> fixedFromProto(fixed)
            custom != null -> customFromProto(custom)
            else -> throw CorruptDataException("infinite page has no origin")
        }
    }

    private fun fixedToProto(spec: PageSpec.Fixed): PbFixed =
        PbFixed(EnumCodec.paperSize(spec.size), EnumCodec.orientation(spec.orientation))

    private fun fixedFromProto(pb: PbFixed): PageSpec.Fixed =
        PageSpec.Fixed(EnumCodec.paperSize(pb.size), EnumCodec.orientation(pb.orientation))

    private fun customFromProto(pb: PbCustom): PageSpec.Custom {
        DecodeLimits.positive(pb.width_pt, DecodeLimits.MAX_PAGE_PT, "custom page width")
        DecodeLimits.positive(pb.height_pt, DecodeLimits.MAX_PAGE_PT, "custom page height")
        return PageSpec.Custom(pb.width_pt, pb.height_pt)
    }
}

internal fun rectToProto(r: RectPt): PbRect = PbRect(r.left, r.top, r.right, r.bottom)

internal fun rectFromProto(
    pb: PbRect?,
    what: String,
): RectPt = pb.orCorrupt(what).let { DecodeLimits.rect(it.left, it.top, it.right, it.bottom, what) }

internal fun pointToProto(p: PointPt): PbPoint = PbPoint(p.x, p.y)

internal fun pointFromProto(pb: PbPoint): PointPt = DecodeLimits.point(pb.x, pb.y, "point")

/** Non-null value or [CorruptDataException] "<what> missing". */
internal fun <T : Any> T?.orCorrupt(what: String): T = this ?: throw CorruptDataException("$what missing")
