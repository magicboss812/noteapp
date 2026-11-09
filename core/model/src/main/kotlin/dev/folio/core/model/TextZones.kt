package dev.folio.core.model

import dev.folio.core.model.GridUnit.Companion.PT_PER_MM
import dev.folio.core.model.geometry.RectPt
import kotlin.math.abs
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

    private const val MIN_STICKY_UNITS = 3

    /** Space between a box's text column (its frame rect) and its outline, left and right, in U (A-059). */
    const val BOX_PADDING_UNITS = 0.5f

    /** Narrowest text column of an auto width box: room for the caret (A-059). */
    const val AUTO_MIN_WIDTH_PT = 4f

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
     * The page text zone free text boxes stay inside (10-editor-ui.md#text-boxes, A-059): the body zone on lined,
     * blank and grid pages; on structured templates (or when the body zone holds no line) the page inside 12 mm
     * margins, top and bottom on rules.
     */
    fun boxZone(
        spec: PageSpec,
        template: Template,
    ): RectPt {
        val body =
            when (template.kind) {
                TemplateKind.CORNELL, TemplateKind.PLANNER_DAILY, TemplateKind.PLANNER_WEEKLY -> null
                else -> bodyRect(spec, template)
            }
        if (body != null) return body
        val grid = GridUnit.of(template)
        val top = ruleAtOrBelow(grid, max(grid.originYPt, SIDE_MARGIN_PT))
        val bottom = max(top + grid.unitPt, ruleAtOrAbove(grid, spec.heightPt - SIDE_MARGIN_PT))
        val right = max(SIDE_MARGIN_PT + BOX_MIN_WIDTH_PT, spec.widthPt - SIDE_MARGIN_PT)
        return RectPt(SIDE_MARGIN_PT, top, right, bottom)
    }

    /**
     * A new auto width box for a tap at ([xPt], [yPt]) (A-059): one line box on the line holding the tap (its text
     * raised by [liftUnits] U), its first glyph at the tap x (the right end for [TextAlign.END], the middle for
     * [TextAlign.CENTER]), [AUTO_MIN_WIDTH_PT] wide, all inside [boxZone]. [anchorOf] gives back the stored anchor.
     */
    fun newBoxAt(
        spec: PageSpec,
        template: Template,
        xPt: Float,
        yPt: Float,
        liftUnits: Float,
        align: TextAlign,
    ): RectPt {
        val zone = boxZone(spec, template)
        val grid = GridUnit.of(template)
        val unitPt = grid.unitPt
        val half = AUTO_MIN_WIDTH_PT / 2f
        val anchor =
            when (align) {
                TextAlign.START, TextAlign.JUSTIFIED -> xPt.coerceIn(zone.left, max(zone.left, zone.right - AUTO_MIN_WIDTH_PT))
                TextAlign.END -> xPt.coerceIn(min(zone.right, zone.left + AUTO_MIN_WIDTH_PT), zone.right)
                TextAlign.CENTER -> xPt.coerceIn(zone.left + half, max(zone.left + half, zone.right - half))
            }
        val borders = autoBorders(zone, anchor, align, 0f)
        val top = ruleAtOrAbove(grid, yPt + liftUnits * unitPt).coerceIn(zone.top, max(zone.top, zone.bottom - unitPt))
        return RectPt(borders.leftPt, top, borders.rightPt, top + unitPt)
    }

    /** The anchor of a box with text column [rect] for [align]: the left edge, the right edge or the middle (A-059). */
    fun anchorOf(
        rect: RectPt,
        align: TextAlign,
    ): Float =
        when (align) {
            TextAlign.START, TextAlign.JUSTIFIED -> rect.left
            TextAlign.END -> rect.right
            TextAlign.CENTER -> (rect.left + rect.right) / 2f
        }

    /**
     * Text column borders of an auto width box anchored at [anchorXPt] whose text needs [contentWidthPt] (at least
     * [AUTO_MIN_WIDTH_PT]): growing right from the anchor for start and justified text, left for end text, both ways
     * for centered text, never past [zone]. [Float.POSITIVE_INFINITY] gives the widest borders the box may take.
     */
    fun autoBorders(
        zone: RectPt,
        anchorXPt: Float,
        align: TextAlign,
        contentWidthPt: Float,
    ): Borders {
        val width = max(AUTO_MIN_WIDTH_PT, contentWidthPt)
        return when (align) {
            TextAlign.START, TextAlign.JUSTIFIED -> {
                Borders(anchorXPt, max(anchorXPt, min(anchorXPt + width, zone.right)))
            }

            TextAlign.END -> {
                Borders(min(anchorXPt, max(anchorXPt - width, zone.left)), anchorXPt)
            }

            TextAlign.CENTER -> {
                val half = max(0f, min(width / 2f, min(anchorXPt - zone.left, zone.right - anchorXPt)))
                Borders(anchorXPt - half, anchorXPt + half)
            }
        }
    }

    /** Hit and outline area of a box with text column [rect] showing [usedUnits] U: padded left and right, at least one line tall. */
    fun boxOutline(
        rect: RectPt,
        usedUnits: Int,
        unitPt: Float,
    ): RectPt {
        val pad = BOX_PADDING_UNITS * unitPt
        return RectPt(rect.left - pad, rect.top, rect.right + pad, rect.top + max(1, usedUnits) * unitPt)
    }

    /**
     * Box text column [rect] dragged with its top-left to ([leftPt], [topPt]) (P07-T13, 10-editor-ui.md#text-boxes):
     * x follows unsnapped, the top moves in whole U onto the nearest rule (the lines keep the flow's lift), same size;
     * the column stays inside [boxZone] (its left at the zone's left when it is wider) and its first line in the zone.
     */
    fun movedBox(
        spec: PageSpec,
        template: Template,
        rect: RectPt,
        leftPt: Float,
        topPt: Float,
    ): RectPt {
        val zone = boxZone(spec, template)
        val grid = GridUnit.of(template)
        val left = leftPt.coerceIn(zone.left, max(zone.left, zone.right - rect.widthPt))
        val first = ruleAtOrBelow(grid, zone.top)
        val last = max(first, ruleAtOrAbove(grid, zone.bottom - grid.unitPt))
        val top = snap(topPt, grid.originYPt, grid.unitPt).coerceIn(first, last)
        return RectPt(left, top, left + rect.widthPt, top + rect.heightPt)
    }

    /**
     * Box text column [rect] moved by arrow keys with its top-left to ([leftPt], [topPt]) (P07-T14): like [movedBox]
     * but the top is kept as given (Shift+Up/Down leave the box between the lines, A-057), between the zone's first
     * rule and the last rule that still has a line box above the zone bottom.
     */
    fun keyedBox(
        spec: PageSpec,
        template: Template,
        rect: RectPt,
        leftPt: Float,
        topPt: Float,
    ): RectPt {
        val zone = boxZone(spec, template)
        val grid = GridUnit.of(template)
        val left = leftPt.coerceIn(zone.left, max(zone.left, zone.right - rect.widthPt))
        val first = ruleAtOrBelow(grid, zone.top)
        val last = max(first, ruleAtOrAbove(grid, zone.bottom - grid.unitPt))
        val top = topPt.coerceIn(first, last)
        return RectPt(left, top, left + rect.widthPt, top + rect.heightPt)
    }

    /**
     * The top one Up ([down] false) or Down key step puts a box top at [topPt] on (P07-T14): the next rule in that
     * direction, so a box between the lines first snaps onto the nearest line on that side.
     */
    fun lineStepTop(
        template: Template,
        topPt: Float,
        down: Boolean,
    ): Float = lineStepTop(GridUnit.of(template), topPt, down)

    /** [lineStepTop] on the rules of [grid] (no allocation: once per key step). */
    fun lineStepTop(
        grid: GridUnit,
        topPt: Float,
        down: Boolean,
    ): Float = if (down) ruleAtOrAbove(grid, topPt) + grid.unitPt else ruleAtOrBelow(grid, topPt) - grid.unitPt

    /** How far a box top at [topPt] sits below its nearest rule (negative above it), 0 on a rule (A-057). */
    fun offsetOf(
        template: Template,
        topPt: Float,
    ): Float {
        val grid = GridUnit.of(template)
        val offset = topPt - snap(topPt, grid.originYPt, grid.unitPt)
        return if (abs(offset) < EPS) 0f else offset
    }

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

    /**
     * Content rect of [sticky] in page pt before its rotation (the painter turns it with the sticky): its rect inset by
     * 0.5 U on every side, so the lines sit at U from the sticky's own top (A-068).
     */
    fun stickyContentRect(
        sticky: StickyNote,
        unitPt: Float,
    ): RectPt {
        val pad = STICKY_PADDING_UNITS * unitPt
        val r = sticky.rect
        return RectPt(r.left + pad, r.top + pad, max(r.left + pad, r.right - pad), max(r.top + pad, r.bottom - pad))
    }

    /**
     * Rect of a new sticky note for a tap at ([xPt], [yPt]) (A-068): a square of whole U as close to
     * [StickyNote.DEFAULT_SIZE_PT] as the grid allows (at least 3 U), its top-left at the tap, moved inside a fixed page.
     */
    fun newStickyAt(
        spec: PageSpec,
        template: Template,
        xPt: Float,
        yPt: Float,
    ): RectPt {
        val unitPt = GridUnit.of(template).unitPt
        val size = max(MIN_STICKY_UNITS, (StickyNote.DEFAULT_SIZE_PT / unitPt).roundToInt()) * unitPt
        if (spec is PageSpec.Infinite) return RectPt.ofSize(xPt, yPt, size, size)
        val left = xPt.coerceIn(0f, max(0f, spec.widthPt - size))
        val top = yPt.coerceIn(0f, max(0f, spec.heightPt - size))
        return RectPt.ofSize(left, top, size, size)
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
