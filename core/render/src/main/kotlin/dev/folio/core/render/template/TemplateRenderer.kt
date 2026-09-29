package dev.folio.core.render.template

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import dev.folio.core.model.GridUnit
import dev.folio.core.model.PageSpec
import dev.folio.core.model.Template
import dev.folio.core.model.TemplateKind
import dev.folio.core.render.template.TemplatePaints.Companion.BOTTOM_MARGIN_PT
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vector templates (05-canvas-rendering.md#templates) aligned to [GridUnit.of]. The canvas must map
 * page pt to device px (scaled by `scale`). Lattice kinds extend infinitely on infinite pages; framed
 * kinds (Cornell, music, planners, custom) repeat their origin frame. Not thread-safe: one instance
 * per drawing thread.
 */
class TemplateRenderer(
    private val assets: TemplateAssets = TemplateAssets.None,
) {
    private val paints = TemplatePaints()
    private val structured = StructuredTemplates(paints)
    private val area = RectF()
    private val frame = RectF()
    private var infinite = false
    private var cachedTemplate: Template? = null
    private var cachedGrid: GridUnit? = null

    /** Draws [template] of a page with [spec] inside [regionPt] (page pt) at [scale] px per pt. */
    fun draw(
        canvas: Canvas,
        template: Template,
        spec: PageSpec,
        regionPt: RectF,
        scale: Float,
    ) {
        // HOT PATH: runs per visible page and frame until P03-T04 moves it into background tiles; no allocation.
        if (template.kind == TemplateKind.BLANK || !(scale > 0f)) return
        infinite = spec is PageSpec.Infinite
        frame.set(0f, 0f, spec.widthPt, spec.heightPt)
        area.set(regionPt)
        if (!infinite && !area.intersect(frame)) return
        if (area.isEmpty) return
        val grid = gridOf(template)
        paints.configure(template.lineArgb, scale)
        canvas.save()
        canvas.clipRect(area)
        when (template.kind) {
            TemplateKind.BLANK -> {
                Unit
            }

            TemplateKind.LINED -> {
                lined(canvas, grid)
            }

            TemplateKind.GRID -> {
                lattice(canvas, grid, axisM = 0, axisN = 0, majorEvery = 0)
            }

            TemplateKind.DOTTED -> {
                dots(canvas, grid, scale)
            }

            TemplateKind.GRAPH_AXES -> {
                val axisM = ((frame.width() / 2f - grid.originXPt) / grid.unitPt).roundToInt()
                val axisN = ((frame.height() / 2f - grid.originYPt) / grid.unitPt).roundToInt()
                lattice(canvas, grid, axisM, axisN, GRAPH_MAJOR_EVERY)
            }

            TemplateKind.CORNELL,
            TemplateKind.MUSIC_STAFF,
            TemplateKind.PLANNER_DAILY,
            TemplateKind.PLANNER_WEEKLY,
            TemplateKind.CUSTOM,
            -> {
                framed(canvas, template, grid)
            }
        }
        canvas.restore()
    }

    private fun gridOf(template: Template): GridUnit {
        val cached = cachedGrid
        if (cached != null && template == cachedTemplate) return cached
        return GridUnit.of(template).also {
            cachedTemplate = template
            cachedGrid = it
        }
    }

    /** Rules from the top margin to the bottom margin (unbounded on infinite pages), optional margin line. */
    private fun lined(
        canvas: Canvas,
        grid: GridUnit,
    ) {
        var first = firstIndex(area.top, grid.originYPt, grid.unitPt)
        var last = lastIndex(area.bottom, grid.originYPt, grid.unitPt)
        if (!infinite) {
            first = max(first, 0)
            last = min(last, lastIndex(frame.bottom - BOTTOM_MARGIN_PT, grid.originYPt, grid.unitPt))
        }
        rows(canvas, grid, first, last, axisN = 0, majorEvery = 0)
        if (grid.originXPt > 0f) canvas.drawLine(grid.originXPt, area.top, grid.originXPt, area.bottom, paints.margin)
    }

    /** Grid lines over the whole area; with [majorEvery] > 0 every n-th line from the axes is darker and the axes darkest. */
    private fun lattice(
        canvas: Canvas,
        grid: GridUnit,
        axisM: Int,
        axisN: Int,
        majorEvery: Int,
    ) {
        val first = firstIndex(area.left, grid.originXPt, grid.unitPt)
        val last = lastIndex(area.right, grid.originXPt, grid.unitPt)
        if (last - first <= MAX_LINES) {
            for (m in first..last) {
                val x = grid.columnXPt(m)
                canvas.drawLine(x, area.top, x, area.bottom, linePaint(m, axisM, majorEvery))
            }
        }
        val firstRow = firstIndex(area.top, grid.originYPt, grid.unitPt)
        rows(canvas, grid, firstRow, lastIndex(area.bottom, grid.originYPt, grid.unitPt), axisN, majorEvery)
    }

    private fun rows(
        canvas: Canvas,
        grid: GridUnit,
        first: Int,
        last: Int,
        axisN: Int,
        majorEvery: Int,
    ) {
        if (last - first > MAX_LINES) return
        for (n in first..last) {
            val y = grid.ruleYPt(n)
            canvas.drawLine(area.left, y, area.right, y, linePaint(n, axisN, majorEvery))
        }
    }

    private fun linePaint(
        index: Int,
        axis: Int,
        majorEvery: Int,
    ): Paint =
        when {
            majorEvery <= 0 -> paints.rule
            index == axis -> paints.axis
            Math.floorMod(index - axis, majorEvery) == 0 -> paints.dark
            else -> paints.rule
        }

    /** One dot per grid intersection; skipped when the pitch is below [MIN_DOT_PITCH_PX] (too dense to read). */
    private fun dots(
        canvas: Canvas,
        grid: GridUnit,
        scale: Float,
    ) {
        if (grid.unitPt * scale < MIN_DOT_PITCH_PX) return
        val firstM = firstIndex(area.left, grid.originXPt, grid.unitPt)
        val lastM = lastIndex(area.right, grid.originXPt, grid.unitPt)
        val firstN = firstIndex(area.top, grid.originYPt, grid.unitPt)
        val lastN = lastIndex(area.bottom, grid.originYPt, grid.unitPt)
        if ((lastM - firstM + 1).toLong() * (lastN - firstN + 1) > MAX_DOTS) return
        for (n in firstN..lastN) {
            val y = grid.ruleYPt(n)
            for (m in firstM..lastM) canvas.drawPoint(grid.columnXPt(m), y, paints.dot)
        }
    }

    /** The origin frame's layout, repeated in both directions on infinite pages. */
    private fun framed(
        canvas: Canvas,
        template: Template,
        grid: GridUnit,
    ) {
        val w = frame.width()
        val h = frame.height()
        if (!(w > 0f && h > 0f)) return
        if (!infinite) {
            drawFrame(canvas, template, grid, w, h)
            return
        }
        val c0 = floor(area.left / w).toInt()
        val c1 = ceil(area.right / w).toInt() - 1
        val r0 = floor(area.top / h).toInt()
        val r1 = ceil(area.bottom / h).toInt() - 1
        if ((c1 - c0 + 1).toLong() * (r1 - r0 + 1) > MAX_REPEATS) return
        for (r in r0..r1) {
            for (c in c0..c1) {
                canvas.save()
                canvas.translate(c * w, r * h)
                drawFrame(canvas, template, grid, w, h)
                canvas.restore()
            }
        }
    }

    private fun drawFrame(
        canvas: Canvas,
        template: Template,
        grid: GridUnit,
        w: Float,
        h: Float,
    ) {
        when (template.kind) {
            TemplateKind.CORNELL -> {
                structured.cornell(canvas, grid, w, h)
            }

            TemplateKind.MUSIC_STAFF -> {
                structured.musicStaff(canvas, grid, w, h)
            }

            TemplateKind.PLANNER_DAILY -> {
                structured.plannerDaily(canvas, grid, w, h)
            }

            TemplateKind.PLANNER_WEEKLY -> {
                structured.plannerWeekly(canvas, grid, w, h)
            }

            TemplateKind.CUSTOM -> {
                val image = template.customAsset?.let(assets::image) ?: return
                canvas.drawBitmap(image, null, frame, paints.bitmap)
            }

            else -> {
                Unit
            }
        }
    }

    // A line that lands on the area edge up to float error (A4 width = 42 * 5 mm) still counts as inside.
    private fun firstIndex(
        fromPt: Float,
        originPt: Float,
        unitPt: Float,
    ): Int = ceil((fromPt - originPt) / unitPt - INDEX_EPS).toInt()

    private fun lastIndex(
        toPt: Float,
        originPt: Float,
        unitPt: Float,
    ): Int = floor((toPt - originPt) / unitPt + INDEX_EPS).toInt()

    private companion object {
        const val INDEX_EPS = 1e-3f
        const val GRAPH_MAJOR_EVERY = 5
        const val MAX_LINES = 4096
        const val MAX_DOTS = 200_000L
        const val MAX_REPEATS = 64L
        const val MIN_DOT_PITCH_PX = 3f
    }
}
