package dev.folio.core.render.template

import dev.folio.core.model.AssetId
import dev.folio.core.model.GridUnit
import dev.folio.core.model.GridUnit.Companion.PT_PER_MM
import dev.folio.core.model.Template
import dev.folio.core.model.TemplateKind

/** Default template geometry and spacing presets per kind (05-canvas-rendering.md#templates). */
object TemplatePresets {
    /** Default rule color. */
    const val LINE_ARGB = 0xFFC9D3E0.toInt()

    /** Default dot color. */
    const val DOT_ARGB = 0xFFB8C0CC.toInt()

    /** Top margin of lined, Cornell, music, planner and blank pages (25 mm). */
    const val TOP_MARGIN_PT = 25f * PT_PER_MM

    /** Position of the lined red margin line (25 mm from the left). */
    const val MARGIN_LINE_PT = 25f * PT_PER_MM

    /** Side margin of music and planner pages (12 mm). */
    const val SIDE_MARGIN_PT = 12f * PT_PER_MM

    /** LINED narrow / college / wide rule spacing (6.0 / 7.1 / 8.7 mm). */
    val LINED_SPACINGS_PT: List<Float> = listOf(6.0f * PT_PER_MM, 7.1f * PT_PER_MM, 8.7f * PT_PER_MM)

    /** GRID and DOTTED spacings (4 / 5 / 7 mm). */
    val LATTICE_SPACINGS_PT: List<Float> = listOf(4f * PT_PER_MM, 5f * PT_PER_MM, 7f * PT_PER_MM)

    /** Selectable spacings of [kind] (page settings sheet); empty when the kind's spacing is fixed. */
    fun spacingsPt(kind: TemplateKind): List<Float> =
        when (kind) {
            TemplateKind.LINED -> LINED_SPACINGS_PT
            TemplateKind.GRID, TemplateKind.DOTTED -> LATTICE_SPACINGS_PT
            else -> emptyList()
        }

    /** The default template of [kind]; CUSTOM needs [customAsset]. */
    fun default(
        kind: TemplateKind,
        customAsset: AssetId? = null,
    ): Template =
        when (kind) {
            TemplateKind.BLANK -> {
                Template(kind, GridUnit.BLANK_UNIT_PT, LINE_ARGB, 0f, TOP_MARGIN_PT, null, null)
            }

            TemplateKind.LINED -> {
                Template(kind, LINED_SPACINGS_PT[1], LINE_ARGB, MARGIN_LINE_PT, TOP_MARGIN_PT, null, null)
            }

            TemplateKind.GRID -> {
                Template(kind, LATTICE_SPACINGS_PT[1], LINE_ARGB, 0f, 0f, null, null)
            }

            TemplateKind.DOTTED -> {
                Template(kind, LATTICE_SPACINGS_PT[1], DOT_ARGB, 0f, 0f, null, null)
            }

            TemplateKind.CORNELL -> {
                Template(kind, GridUnit.COLLEGE_UNIT_PT, LINE_ARGB, 0f, TOP_MARGIN_PT, null, null)
            }

            TemplateKind.GRAPH_AXES -> {
                Template(kind, GridUnit.GRAPH_UNIT_PT, LINE_ARGB, 0f, 0f, null, null)
            }

            TemplateKind.MUSIC_STAFF -> {
                Template(kind, GridUnit.BLANK_UNIT_PT, LINE_ARGB, SIDE_MARGIN_PT, TOP_MARGIN_PT, null, null)
            }

            TemplateKind.PLANNER_DAILY, TemplateKind.PLANNER_WEEKLY -> {
                Template(kind, GridUnit.COLLEGE_UNIT_PT, LINE_ARGB, SIDE_MARGIN_PT, TOP_MARGIN_PT, null, null)
            }

            TemplateKind.CUSTOM -> {
                Template(kind, GridUnit.COLLEGE_UNIT_PT, LINE_ARGB, 0f, 0f, customAsset, GridUnit.COLLEGE_UNIT_PT)
            }
        }
}
