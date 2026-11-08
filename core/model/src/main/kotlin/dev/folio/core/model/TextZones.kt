package dev.folio.core.model

import dev.folio.core.model.GridUnit.Companion.PT_PER_MM
import dev.folio.core.model.geometry.RectPt
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Text frame geometry (07-text-engine.md#flows-and-frames): the body text zone of a template, free text boxes and
 * the content area of sticky notes. Every frame top sits on a rule, so line boxes stacked from it keep their
 * baselines on rules. Cornell and planner numbers mirror core:render `StructuredTemplates`.
 */
@Suppress("TooManyFunctions") // one place for every frame rect and its snapping to the rules and columns
object TextZones {
    /** Side and bottom margin of body zones (12 mm). */
    const val SIDE_MARGIN_PT = 12f * PT_PER_MM

    /** Gap between a margin line (or column line) and the text (2 mm). */
    const val LINE_GAP_PT = 2f * PT_PER_MM

    /** Narrowest free text box (20 mm). */
    const val BOX_MIN_WIDTH_PT = 20f * PT_PER_MM

    /** Default free text box width as a fraction of the body zone width. */
    const val BOX_WIDTH_FRACTION = 0.6f

    /** Inner padding of a sticky note in U. */
    const val STICKY_PADDING_UNITS = 0.5f

    private const val CORNELL_CUE_PT = 63.5f * PT_PER_MM
    private const val CORNELL_SUMMARY_PT = 50.8f * PT_PER_MM
    private const val PLANNER_SCHEDULE_FRACTION = 0.6f
    private const val PLANNER_BOX_ROWS = 4
    private const val PLANNER_MIN_BOX_RULES = 2
    private const val EPS = 1e-3f // float error of rules that land exactly on a limit

    /**
     * Body frame rect of a page: lined/blank: from the margin line + 2 mm (12 mm without one) to width - 12 mm;
     * grid/dotted/graph: 12 mm margins snapped inward to grid columns; Cornell: the notes column above the summary;
     * daily planner: the notes column; weekly planner: the notes box. Top = first rule at or below the template
     * origin and 12 mm, bottom = last rule above height - 12 mm. Infinite pages use their origin frame (the flow
     * treats the height as unbounded). Null when the zone holds no line.
     */
    fun bodyRect(
        spec: PageSpec,
        template: Template,
    ): RectPt? {
        val grid = GridUnit.of(template)
        val w = spec.widthPt
        val h = spec.heightPt
        val top = ruleAtOrBelow(grid, max(grid.originYPt, SIDE_MARGIN_PT))
        val bottom = ruleAtOrAbove(grid, h - SIDE_MARGIN_PT)
        val rect =
            when (template.kind) {
                TemplateKind.GRID, TemplateKind.DOTTED, TemplateKind.GRAPH_AXES -> {
                    RectPt(columnAtOrRight(grid, SIDE_MARGIN_PT), top, columnAtOrLeft(grid, w - SIDE_MARGIN_PT), bottom)
                }

                TemplateKind.CORNELL -> {
                    val summary = max(grid.originYPt, min(ruleAtOrAbove(grid, h - CORNELL_SUMMARY_PT), bottom))
                    RectPt(min(CORNELL_CUE_PT, w / 2f) + LINE_GAP_PT, grid.originYPt, w - SIDE_MARGIN_PT, summary)
                }

                TemplateKind.PLANNER_DAILY -> {
                    val left = grid.originXPt
                    val notes = left + (w - 2f * left) * PLANNER_SCHEDULE_FRACTION
                    RectPt(notes + LINE_GAP_PT, grid.originYPt, w - left, ruleAtOrAbove(grid, h - SIDE_MARGIN_PT))
                }

                TemplateKind.PLANNER_WEEKLY -> {
                    weeklyNotes(grid, w, h)
                }

                TemplateKind.LINED, TemplateKind.BLANK, TemplateKind.MUSIC_STAFF, TemplateKind.CUSTOM -> {
                    val left = if (grid.originXPt > 0f) grid.originXPt + LINE_GAP_PT else SIDE_MARGIN_PT
                    RectPt(left, top, w - SIDE_MARGIN_PT, bottom)
                }
            } ?: return null
        val holdsLine = rect.bottom - rect.top >= grid.unitPt - EPS && rect.right - rect.left >= BOX_MIN_WIDTH_PT
        return if (holdsLine) rect else null
    }

    /** Whole line boxes of U that fit into [heightPt]. */
    fun capacityUnits(
        heightPt: Float,
        unitPt: Float,
    ): Int = max(0, floor(heightPt / unitPt + EPS).toInt())

    /**
     * A new one-line free text box for a tap at ([xPt], [yPt]): top on the rule at or above the tap, left on the
     * grid column at or left of it (free on lined pages), 60% of the body zone wide (at least 20 mm), kept inside
     * the page.
     */
    fun newBoxRect(
        spec: PageSpec,
        template: Template,
        xPt: Float,
        yPt: Float,
    ): RectPt {
        val grid = GridUnit.of(template)
        val bodyWidth = bodyRect(spec, template)?.widthPt ?: (spec.widthPt - 2f * SIDE_MARGIN_PT)
        val width = min(max(BOX_MIN_WIDTH_PT, bodyWidth * BOX_WIDTH_FRACTION), spec.widthPt)
        val maxLeft = spec.widthPt - width
        var left = min(max(0f, xPt), maxLeft)
        if (grid.snapsColumns) {
            left = columnAtOrLeft(grid, left)
            if (left < 0f) left = columnAtOrRight(grid, 0f)
        }
        val top = max(ruleAtOrAbove(grid, yPt), ruleAtOrBelow(grid, 0f))
        return RectPt(left, top, left + width, top + grid.unitPt)
    }

    /**
     * Free text box [rect] dropped with its top-left near ([leftPt], [topPt]) (P06-T17): the top on the nearest rule,
     * the left on the nearest grid column (1 mm steps on pages without columns), same size, kept inside the page as
     * far as the box fits.
     */
    fun movedBox(
        spec: PageSpec,
        template: Template,
        rect: RectPt,
        leftPt: Float,
        topPt: Float,
    ): RectPt {
        val grid = GridUnit.of(template)
        val step = columnStep(grid)
        var left = snap(min(leftPt, spec.widthPt - rect.widthPt), columnOrigin(grid), step)
        if (left + rect.widthPt > spec.widthPt) left -= step * ceil((left + rect.widthPt - spec.widthPt) / step - EPS)
        if (left < 0f) left += step * ceil(-left / step - EPS)
        var top = snap(min(topPt, spec.heightPt - rect.heightPt), grid.originYPt, grid.unitPt)
        if (top + rect.heightPt > spec.heightPt) top = ruleAtOrAbove(grid, spec.heightPt - rect.heightPt)
        if (top < 0f) top = ruleAtOrBelow(grid, 0f)
        return RectPt(left, top, left + rect.widthPt, top + rect.heightPt)
    }

    /**
     * Free text box [rect] with its left and right edges dragged to [leftPt] and [rightPt] (P06-T17): each moved edge
     * snaps to the nearest grid column (1 mm on pages without columns), inside the page, at least 20 mm wide. The
     * caller reflows the box, whose height follows in whole U.
     */
    fun resizedBox(
        spec: PageSpec,
        template: Template,
        rect: RectPt,
        leftPt: Float,
        rightPt: Float,
    ): RectPt {
        val grid = GridUnit.of(template)
        val step = columnStep(grid)
        val minWidth = step * ceil(BOX_MIN_WIDTH_PT / step - EPS)
        var left = rect.left
        var right = rect.right
        if (leftPt != rect.left) {
            left = snap(min(leftPt, right - minWidth), columnOrigin(grid), step)
            if (left < 0f) left += step * ceil(-left / step - EPS)
        }
        if (rightPt != rect.right) {
            right = snap(max(rightPt, left + minWidth), columnOrigin(grid), step)
            if (right > spec.widthPt) right -= step * ceil((right - spec.widthPt) / step - EPS)
        }
        if (right - left < minWidth - EPS) right = left + minWidth
        return rect.copy(left = left, right = right)
    }

    // Columns a box edge snaps to: the grid columns, or millimeters from the page edge.
    private fun columnStep(grid: GridUnit): Float = if (grid.snapsColumns) grid.unitPt else PT_PER_MM

    private fun columnOrigin(grid: GridUnit): Float = if (grid.snapsColumns) grid.originXPt else 0f

    private fun snap(
        valuePt: Float,
        originPt: Float,
        stepPt: Float,
    ): Float = originPt + ((valuePt - originPt) / stepPt).roundToInt() * stepPt

    /** [rect] grown or shrunk to [units] whole line boxes (at least one). */
    fun grownBox(
        rect: RectPt,
        units: Int,
        unitPt: Float,
    ): RectPt = rect.copy(bottom = rect.top + max(1, units) * unitPt)

    /** Content rect of [sticky] in its own unrotated coordinates (top-left = 0, 0): inset by 0.5 U on every side. */
    fun stickyContentRect(
        sticky: StickyNote,
        unitPt: Float,
    ): RectPt {
        val pad = STICKY_PADDING_UNITS * unitPt
        return RectPt(pad, pad, max(pad, sticky.rect.widthPt - pad), max(pad, sticky.rect.heightPt - pad))
    }

    private fun weeklyNotes(
        grid: GridUnit,
        w: Float,
        h: Float,
    ): RectPt? {
        val u = grid.unitPt
        val left = grid.originXPt
        val right = w - grid.originXPt
        val totalRules = floor((h - SIDE_MARGIN_PT - grid.originYPt) / u + EPS).toInt()
        val boxRules = totalRules / PLANNER_BOX_ROWS
        if (boxRules < PLANNER_MIN_BOX_RULES || right <= left) return null
        val top = grid.originYPt + (PLANNER_BOX_ROWS - 1) * boxRules * u
        return RectPt((left + right) / 2f + LINE_GAP_PT, top, right - LINE_GAP_PT, top + boxRules * u)
    }

    private fun ruleAtOrBelow(
        grid: GridUnit,
        yPt: Float,
    ): Float = grid.ruleYPt(ceil((yPt - grid.originYPt) / grid.unitPt - EPS).toInt())

    private fun ruleAtOrAbove(
        grid: GridUnit,
        yPt: Float,
    ): Float = grid.ruleYPt(floor((yPt - grid.originYPt) / grid.unitPt + EPS).toInt())

    private fun columnAtOrRight(
        grid: GridUnit,
        xPt: Float,
    ): Float = grid.columnXPt(ceil((xPt - grid.originXPt) / grid.unitPt - EPS).toInt())

    private fun columnAtOrLeft(
        grid: GridUnit,
        xPt: Float,
    ): Float = grid.columnXPt(floor((xPt - grid.originXPt) / grid.unitPt + EPS).toInt())
}
