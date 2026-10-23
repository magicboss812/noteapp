package dev.folio.core.text.layout

import androidx.compose.ui.graphics.Color
import dev.folio.core.model.GridUnit
import dev.folio.core.text.fonts.BodySize
import dev.folio.core.text.fonts.FontRegistry

/** Horizontal alignment of a flow (07-text-engine.md#block-styles); JUSTIFIED applies to paragraphs only. */
enum class FlowAlignment { START, CENTER, JUSTIFIED }

/**
 * Colors baked into span styles. Body text color is chosen at draw time; links, highlight bands and
 * inline code backgrounds are part of the layout.
 */
data class TextPalette(
    val link: Color,
    val highlight: Color,
    val codeBackground: Color,
    /** Source of LaTeX that does not parse (07-text-engine.md#math). */
    val mathError: Color = MATH_ERROR,
    /** Note links whose document is not in the library (09-storage-library.md#links); drawn struck through. */
    val brokenLink: Color = BROKEN_LINK,
) {
    companion object {
        private val MATH_ERROR = Color(0xFFD0342C)
        private val BROKEN_LINK = Color(0xFF8E96A3)

        /** Ink-on-paper defaults: blue links, marker yellow, light gray code, red invalid LaTeX. */
        val Paper = TextPalette(link = Color(0xFF2F6FDE), highlight = Color(0x66FFE14D), codeBackground = Color(0x1F000000))
    }
}

/**
 * Per-flow text settings; part of the layout cache key. [familyName] goes through the
 * [FontRegistry] fallback chain; [bodyCap] is a [BodySize] preset or a free value (A-039).
 */
data class FlowTextStyle(
    val familyName: String = FontRegistry.DEFAULT_FAMILY,
    val bodyCap: Float = BodySize.M.capFraction,
    val alignment: FlowAlignment = FlowAlignment.START,
    val palette: TextPalette = TextPalette.Paper,
    /**
     * Document ids whose note links are broken (no such document in the library). The layout cache keys a block only
     * by the ids it links to, so a change of this set lays out just the blocks whose links changed state.
     */
    val brokenLinks: Set<String> = emptySet(),
)

/**
 * Grid a block is laid out on: line boxes are whole multiples of [unitPt]; list and quote indents are
 * whole multiples of [indentStepPt] (07-text-engine.md#blocks).
 */
data class LayoutGrid(
    val unitPt: Float,
    val indentStepPt: Float,
) {
    companion object {
        /** Indent step on templates without grid columns (lined, blank). */
        const val LINED_INDENT_PT = 6f * GridUnit.PT_PER_MM

        /** One grid column on grid/dotted templates, 6 mm elsewhere. */
        fun of(unit: GridUnit): LayoutGrid = LayoutGrid(unit.unitPt, if (unit.snapsColumns) unit.unitPt else LINED_INDENT_PT)
    }
}
