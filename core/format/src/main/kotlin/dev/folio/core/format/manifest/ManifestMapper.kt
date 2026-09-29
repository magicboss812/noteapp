package dev.folio.core.format.manifest

import dev.folio.core.format.codec.CorruptDataException
import dev.folio.core.format.codec.DecodeLimits
import dev.folio.core.format.codec.corruptIf
import dev.folio.core.format.manifest.ManifestValues.formatArgb
import dev.folio.core.format.manifest.ManifestValues.parseArgb
import dev.folio.core.model.AssetId
import dev.folio.core.model.AssetInfo
import dev.folio.core.model.Background
import dev.folio.core.model.DocId
import dev.folio.core.model.Document
import dev.folio.core.model.DocumentMeta
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.PdfBackground
import dev.folio.core.model.Template
import dev.folio.core.model.TemplateKind
import dev.folio.core.model.TextAlign
import dev.folio.core.model.geometry.RectPt
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.collections.immutable.toPersistentSet

/** Document <-> [Manifest]. Decoding throws [CorruptDataException]; callers wrap it into `FormatError.Corrupt`. */
internal object ManifestMapper {
    private const val DEFAULT_LINE_ARGB = 0xFFC9D3E0.toInt()
    private const val RECT_VALUES = 4

    fun fromDocument(
        doc: Document,
        app: ManifestApp,
        links: List<String>,
        textPreview: String,
    ): Manifest {
        val defaultSpec = SpecFields.of(doc.meta.defaultPageSpec)
        val defaultBg = backgroundToJson(doc.meta.defaultBackground)
        return Manifest(
            formatVersion = Manifest.CURRENT_VERSION,
            id = doc.meta.id.value,
            title = doc.meta.title,
            createdMs = doc.meta.createdMs,
            modifiedMs = doc.meta.modifiedMs,
            app = app,
            tags = doc.meta.tags.toList(),
            favorite = doc.meta.favorite,
            pages = doc.pages.map(::pageToJson),
            flows = doc.flows.keys.map { it.value },
            assets = doc.assets.values.map { ManifestAsset(it.id.value, ManifestValues.assetPath(it), it.mime, it.bytes, it.originalName) },
            links = links,
            textPreview = textPreview,
            defaults =
                ManifestDefaults(
                    kind = defaultSpec.kind,
                    size = defaultSpec.size,
                    orientation = defaultSpec.orientation,
                    widthPt = defaultSpec.widthPt,
                    heightPt = defaultSpec.heightPt,
                    origin = defaultSpec.origin,
                    template = defaultBg.template.kind,
                    spacingPt = defaultBg.template.spacingPt,
                    paperArgb = defaultBg.paperArgb,
                    background = defaultBg,
                ),
        )
    }

    /** Meta, page refs and asset infos; flows are read from their own entries. */
    fun toDocumentShell(m: Manifest): Document {
        corruptIf(m.format != Manifest.FORMAT_NAME) { "format is '${m.format}'" }
        corruptIf(m.id.isBlank()) { "document id missing" }
        val d = m.defaults
        val defaultSpec = SpecFields(d.kind, d.size, d.orientation, d.widthPt, d.heightPt, d.origin).toSpec()
        val defaultBg =
            d.background?.let(::backgroundFromJson)
                ?: Background(parseArgb(d.paperArgb), fallbackTemplate(d.template, d.spacingPt), null)
        val meta =
            DocumentMeta(
                id = DocId(m.id),
                title = m.title,
                createdMs = m.createdMs,
                modifiedMs = m.modifiedMs,
                tags = m.tags.toPersistentSet(),
                favorite = m.favorite,
                formatVersion = m.formatVersion,
                defaultPageSpec = defaultSpec,
                defaultBackground = defaultBg,
            )
        val pages = m.pages.map { pageFromJson(it, defaultBg) }
        corruptIf(pages.distinctBy { it.id }.size != pages.size) { "duplicate page ids" }
        val assets = m.assets.associate { AssetId(it.id) to AssetInfo(AssetId(it.id), it.mime, it.bytes, it.name) }
        return Document(meta, pages.toPersistentList(), persistentMapOf(), assets.toPersistentMap())
    }

    fun flowStyleToJson(
        style: FlowStyle,
        autoContinue: Boolean,
    ): FlowStyleJson = FlowStyleJson(style.fontFamily, style.sizeRatio, style.paragraphGapLines, style.align.name.lowercase(), autoContinue)

    fun flowStyleFromJson(json: FlowStyleJson): FlowStyle {
        val align = TextAlign.entries.firstOrNull { it.name.equals(json.align, ignoreCase = true) } ?: TextAlign.START
        return FlowStyle(json.fontFamily, json.sizeRatio, json.paragraphGapLines, align)
    }

    private fun pageToJson(ref: PageRef): ManifestPage {
        val f = SpecFields.of(ref.spec)
        val bg = backgroundToJson(ref.background)
        return ManifestPage(
            id = ref.id.value,
            kind = f.kind,
            size = f.size,
            orientation = f.orientation,
            widthPt = ref.spec.widthPt,
            heightPt = ref.spec.heightPt,
            origin = f.origin,
            template = bg.template.kind,
            pdf = bg.pdf,
            contentBounds = ref.contentBounds?.let { listOf(it.left, it.top, it.right, it.bottom) },
            background = bg,
        )
    }

    private fun pageFromJson(
        p: ManifestPage,
        defaultBg: Background,
    ): PageRef {
        corruptIf(p.id.isBlank()) { "page id missing" }
        val spec = SpecFields(p.kind, p.size, p.orientation, p.widthPt, p.heightPt, p.origin).toSpec()
        val background =
            p.background?.let(::backgroundFromJson)
                ?: Background(
                    defaultBg.paperArgb,
                    fallbackTemplate(p.template, defaultBg.template.spacingPt),
                    p.pdf?.let { PdfBackground(AssetId(it.asset), it.pageIndex) },
                )
        val bounds =
            p.contentBounds?.let {
                corruptIf(it.size != RECT_VALUES) { "contentBounds needs 4 values" }
                DecodeLimits.rect(it[0], it[1], it[2], it[3], "contentBounds")
            }
        return PageRef(PageId(p.id), spec, background, if (spec is PageSpec.Infinite) bounds else null)
    }

    private fun backgroundToJson(bg: Background): ManifestBackground =
        ManifestBackground(
            paperArgb = formatArgb(bg.paperArgb),
            template =
                ManifestTemplate(
                    kind = bg.template.kind.name,
                    spacingPt = bg.template.spacingPt,
                    lineArgb = formatArgb(bg.template.lineArgb),
                    marginLeftPt = bg.template.marginLeftPt,
                    marginTopPt = bg.template.marginTopPt,
                    customAsset = bg.template.customAsset?.value,
                    customGridPt = bg.template.customGridPt,
                ),
            pdf = bg.pdf?.let { ManifestPdf(it.asset.value, it.pageIndex) },
        )

    private fun backgroundFromJson(bg: ManifestBackground): Background {
        val t = bg.template
        val template =
            Template(
                kind = templateKind(t.kind),
                spacingPt = t.spacingPt,
                lineArgb = parseArgb(t.lineArgb),
                marginLeftPt = t.marginLeftPt,
                marginTopPt = t.marginTopPt,
                customAsset = t.customAsset?.let(::AssetId),
                customGridPt = t.customGridPt,
            )
        return Background(parseArgb(bg.paperArgb), template, bg.pdf?.let { PdfBackground(AssetId(it.asset), it.pageIndex) })
    }

    private fun fallbackTemplate(
        kind: String,
        spacingPt: Float,
    ): Template = Template(templateKind(kind), spacingPt, DEFAULT_LINE_ARGB, 0f, 0f, null, null)

    private fun templateKind(name: String): TemplateKind = TemplateKind.entries.firstOrNull { it.name == name } ?: TemplateKind.BLANK
}

/** Page spec as manifest fields. */
internal data class SpecFields(
    val kind: String,
    val size: String?,
    val orientation: String?,
    val widthPt: Float?,
    val heightPt: Float?,
    val origin: String?,
) {
    fun toSpec(): PageSpec =
        when (kind) {
            "fixed" -> fixed()
            "custom" -> custom()
            "infinite" -> PageSpec.Infinite(if (origin == "custom") custom() else fixed())
            else -> throw CorruptDataException("unknown page kind '$kind'")
        }

    private fun fixed(): PageSpec.Fixed {
        val paper = PaperSize.entries.firstOrNull { it.name == size } ?: throw CorruptDataException("unknown paper size '$size'")
        val orient = if (orientation == "landscape") Orientation.LANDSCAPE else Orientation.PORTRAIT
        return PageSpec.Fixed(paper, orient)
    }

    private fun custom(): PageSpec.Custom {
        val w = widthPt
        val h = heightPt
        val max = DecodeLimits.MAX_PAGE_PT
        corruptIf(w == null || h == null || !(w > 0f && w <= max && h > 0f && h <= max)) { "custom page size $w x $h" }
        return PageSpec.Custom(w ?: 0f, h ?: 0f)
    }

    companion object {
        fun of(spec: PageSpec): SpecFields =
            when (spec) {
                is PageSpec.Fixed -> {
                    SpecFields(
                        "fixed",
                        spec.size.name,
                        spec.orientation.name.lowercase(),
                        spec.widthPt,
                        spec.heightPt,
                        null,
                    )
                }

                is PageSpec.Custom -> {
                    SpecFields("custom", null, null, spec.widthPt, spec.heightPt, null)
                }

                is PageSpec.Infinite -> {
                    of(spec.origin).copy(kind = "infinite", origin = of(spec.origin).kind)
                }
            }
    }
}
