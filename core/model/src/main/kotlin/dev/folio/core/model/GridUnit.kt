package dev.folio.core.model

/**
 * Grid unit U and grid origin of a page template (07-text-engine.md#grid-unit): rules lie at
 * [originYPt] + n * [unitPt]; grid columns (only when [snapsColumns]) at [originXPt] + m * [unitPt].
 */
data class GridUnit(
    val unitPt: Float,
    val originXPt: Float,
    val originYPt: Float,
    val snapsColumns: Boolean,
) {
    /** y of rule [n] in page pt. */
    fun ruleYPt(n: Int): Float = originYPt + n * unitPt

    /** x of grid column [m] in page pt. */
    fun columnXPt(m: Int): Float = originXPt + m * unitPt

    companion object {
        /** PDF points per millimeter. */
        const val PT_PER_MM = 72f / 25.4f

        /** U of BLANK and MUSIC_STAFF pages (7.0 mm). */
        const val BLANK_UNIT_PT = 7.0f * PT_PER_MM

        /** College rule spacing (7.1 mm): U of CORNELL, planners, and the CUSTOM default. */
        const val COLLEGE_UNIT_PT = 7.1f * PT_PER_MM

        /** U of GRAPH_AXES (5 mm). */
        const val GRAPH_UNIT_PT = 5.0f * PT_PER_MM

        /** Smallest accepted U (2 mm); smaller or invalid decoded values fall back to the kind's default (D-008). */
        const val MIN_UNIT_PT = 2f * PT_PER_MM

        /** Largest accepted U (50 mm). */
        const val MAX_UNIT_PT = 50f * PT_PER_MM

        /** Largest accepted template margin (the A3 long side). */
        const val MAX_MARGIN_PT = 1190.5512f

        /** The single source of U and the grid origin for [template]. Tolerates untrusted (decoded) values. */
        fun of(template: Template): GridUnit {
            val unitPt =
                when (template.kind) {
                    TemplateKind.LINED, TemplateKind.GRID, TemplateKind.DOTTED -> sane(template.spacingPt, COLLEGE_UNIT_PT)
                    TemplateKind.BLANK, TemplateKind.MUSIC_STAFF -> BLANK_UNIT_PT
                    TemplateKind.GRAPH_AXES -> GRAPH_UNIT_PT
                    TemplateKind.CORNELL, TemplateKind.PLANNER_DAILY, TemplateKind.PLANNER_WEEKLY -> COLLEGE_UNIT_PT
                    TemplateKind.CUSTOM -> sane(template.customGridPt ?: COLLEGE_UNIT_PT, COLLEGE_UNIT_PT)
                }
            val snaps =
                template.kind == TemplateKind.GRID || template.kind == TemplateKind.DOTTED || template.kind == TemplateKind.GRAPH_AXES
            return GridUnit(unitPt, margin(template.marginLeftPt), margin(template.marginTopPt), snaps)
        }

        private fun sane(
            valuePt: Float,
            fallbackPt: Float,
        ): Float = if (valuePt.isFinite() && valuePt > 0f) valuePt.coerceIn(MIN_UNIT_PT, MAX_UNIT_PT) else fallbackPt

        private fun margin(valuePt: Float): Float = if (valuePt.isFinite() && valuePt > 0f) valuePt.coerceAtMost(MAX_MARGIN_PT) else 0f
    }
}
