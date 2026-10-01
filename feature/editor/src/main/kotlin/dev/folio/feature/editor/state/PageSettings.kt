package dev.folio.feature.editor.state

import androidx.compose.runtime.Immutable
import dev.folio.core.model.Background
import dev.folio.core.model.Document
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.TemplateKind
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.PageOps
import dev.folio.core.model.edit.UpdateMeta
import dev.folio.core.render.template.TemplatePresets
import kotlin.math.abs

/** Paper colors of the page settings sheet (10-editor-ui.md#pages). */
enum class PaperColor(
    val label: String,
    val argb: Int,
) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    CREAM("Cream", 0xFFFFF8E7.toInt()),
    LIGHT_GRAY("Light gray", 0xFFF1F2F4.toInt()),
    LIGHT_GREEN("Light green", 0xFFEAF5EA.toInt()),
    DARK("Dark", 0xFF2B2D31.toInt()),
}

/** Which pages the page settings sheet changes. */
enum class ApplyTo(
    val label: String,
) {
    THIS_PAGE("This page"),
    ALL_PAGES("All pages"),
    NEW_PAGES("New pages"),
}

/**
 * The values the page settings sheet edits (10-editor-ui.md#pages). [sizeEditable] is false for pages whose size
 * is not an ISO paper size (PDF pages, infinite canvas): their size and orientation show read-only.
 */
@Immutable
data class PageSettings(
    val size: PaperSize,
    val orientation: Orientation,
    val sizeEditable: Boolean,
    val template: TemplateKind,
    val spacingPt: Float,
    val paperArgb: Int,
    val marginLine: Boolean,
) {
    /** The settings as they are on [ref]. */
    constructor(ref: PageRef) : this(
        size = (ref.spec as? PageSpec.Fixed)?.size ?: PaperSize.A4,
        orientation = (ref.spec as? PageSpec.Fixed)?.orientation ?: Orientation.PORTRAIT,
        sizeEditable = ref.spec is PageSpec.Fixed && ref.background.pdf == null,
        template = ref.background.template.kind,
        spacingPt = ref.background.template.spacingPt,
        paperArgb = ref.background.paperArgb,
        marginLine = ref.background.template.marginLeftPt > 0f,
    )

    /** Same settings with template [kind]: its default spacing and margin line. */
    fun withTemplate(kind: TemplateKind): PageSettings {
        if (kind == template) return this
        val preset = TemplatePresets.default(kind)
        return copy(template = kind, spacingPt = preset.spacingPt, marginLine = preset.marginLeftPt > 0f)
    }

    /** Whether the template has a margin line to switch (the kinds whose preset has a left margin). */
    val hasMarginLine: Boolean get() = TemplatePresets.default(template).marginLeftPt > 0f

    /** The spec of page [ref] after applying these settings (only fixed ISO pages without a PDF change). */
    fun specFor(ref: PageRef): PageSpec =
        if (sizeEditable && ref.spec is PageSpec.Fixed && ref.background.pdf == null) PageSpec.Fixed(size, orientation) else ref.spec

    /** The background of a page now at [current] after applying these settings (a PDF page stays under the template). */
    fun backgroundFor(current: Background): Background {
        val base = current.template
        val preset = TemplatePresets.default(template, base.customAsset)
        val kept = if (base.kind == template) base else preset
        val margin =
            when {
                !marginLine -> 0f
                kept.marginLeftPt > 0f -> kept.marginLeftPt
                else -> preset.marginLeftPt
            }
        val spacing = if (template == TemplateKind.BLANK || template == TemplateKind.CUSTOM) kept.spacingPt else spacingPt
        return current.copy(paperArgb = paperArgb, template = kept.copy(spacingPt = spacing, marginLeftPt = margin))
    }

    /**
     * The one-undo-step command that applies these settings; null if nothing would change. [applyTo] picks
     * [page] only, every page, or the defaults for new pages (document metadata). The pages' bodies must be
     * loaded for the first two (see [pagesToLoad]).
     */
    fun commandFor(
        doc: Document,
        page: PageId,
        applyTo: ApplyTo,
    ): EditCommand? =
        when (applyTo) {
            ApplyTo.THIS_PAGE -> {
                restyle(doc, listOf(page))
            }

            ApplyTo.ALL_PAGES -> {
                restyle(doc, doc.pages.map { it.id })
            }

            ApplyTo.NEW_PAGES -> {
                val meta = doc.meta
                val spec = if (sizeEditable) PageSpec.Fixed(size, orientation) else meta.defaultPageSpec
                val updated = meta.copy(defaultPageSpec = spec, defaultBackground = backgroundFor(meta.defaultBackground))
                if (updated == meta) null else UpdateMeta(updated)
            }
        }

    /** The pages [commandFor] needs decoded. */
    fun pagesToLoad(
        doc: Document,
        page: PageId,
        applyTo: ApplyTo,
    ): List<PageId> =
        when (applyTo) {
            ApplyTo.THIS_PAGE -> listOf(page)
            ApplyTo.ALL_PAGES -> doc.pages.map { it.id }
            ApplyTo.NEW_PAGES -> emptyList()
        }

    private fun restyle(
        doc: Document,
        ids: List<PageId>,
    ): EditCommand? = PageOps.restyle(doc, ids, { specFor(it) }, { backgroundFor(it.background) })

    /** Selectable spacing presets of the current template, with a stored value that is not one of them first. */
    fun spacingChoices(): List<Float> {
        val presets = TemplatePresets.spacingsPt(template)
        return if (presets.isEmpty() || presets.any { abs(it - spacingPt) < SPACING_EPSILON }) presets else listOf(spacingPt) + presets
    }

    private companion object {
        const val SPACING_EPSILON = 0.01f
    }
}
