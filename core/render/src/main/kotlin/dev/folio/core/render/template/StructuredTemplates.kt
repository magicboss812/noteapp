package dev.folio.core.render.template

import android.graphics.Canvas
import dev.folio.core.model.GridUnit
import dev.folio.core.model.GridUnit.Companion.PT_PER_MM
import dev.folio.core.model.PaperSize
import dev.folio.core.render.template.TemplatePaints.Companion.BOTTOM_MARGIN_PT
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Templates with a page layout (Cornell, music staff, planners), drawn once per origin frame of
 * [widthPt] x [heightPt] in page pt. Every ruled line sits on a rule of [GridUnit]. Not thread-safe.
 */
internal class StructuredTemplates(
    private val paints: TemplatePaints,
) {
    /** College rules, cue column left, summary area at the bottom, 1 pt separators. */
    fun cornell(
        canvas: Canvas,
        grid: GridUnit,
        widthPt: Float,
        heightPt: Float,
    ) {
        val u = grid.unitPt
        val y0 = grid.originYPt
        val lastRule = rulesUntil(grid, heightPt - BOTTOM_MARGIN_PT)
        if (lastRule < 1) return
        for (n in 1..lastRule) canvas.drawLine(0f, y0 + n * u, widthPt, y0 + n * u, paints.rule)
        val summaryY = y0 + rulesUntil(grid, heightPt - SUMMARY_PT).coerceIn(0, lastRule) * u
        canvas.drawLine(0f, y0, widthPt, y0, paints.strong)
        canvas.drawLine(0f, summaryY, widthPt, summaryY, paints.strong)
        val cueX = min(CUE_COLUMN_PT, widthPt / 2f)
        canvas.drawLine(cueX, y0, cueX, summaryY, paints.strong)
    }

    /** 10 staves per A4 portrait height (scaled to [heightPt]), 5 lines 2 mm apart, between the side margins. */
    fun musicStaff(
        canvas: Canvas,
        grid: GridUnit,
        widthPt: Float,
        heightPt: Float,
    ) {
        val top = grid.originYPt
        val bottom = heightPt - MUSIC_BOTTOM_PT
        val staves = max(1, (STAVES_PER_A4 * heightPt / PaperSize.A4.heightPt).roundToInt())
        val slotPt = (bottom - top) / staves
        val left = grid.originXPt
        val right = widthPt - grid.originXPt
        if (slotPt < STAFF_HEIGHT_PT || right <= left || staves > MAX_STAVES) return
        for (s in 0 until staves) {
            val staffTop = top + s * slotPt + (slotPt - STAFF_HEIGHT_PT) / 2f
            for (line in 0 until STAFF_LINES) {
                val y = staffTop + line * STAFF_GAP_PT
                canvas.drawLine(left, y, right, y, paints.dark)
            }
        }
    }

    /** Date header, hour rows 06:00-22:00 with labels in a 15 mm gutter, lined notes column. */
    fun plannerDaily(
        canvas: Canvas,
        grid: GridUnit,
        widthPt: Float,
        heightPt: Float,
    ) {
        val u = grid.unitPt
        val y0 = grid.originYPt
        val left = grid.originXPt
        val right = widthPt - grid.originXPt
        val totalRules = rulesUntil(grid, heightPt - BOTTOM_MARGIN_PT)
        val rowRules = totalRules / HOUR_LABELS.size
        if (rowRules < 1 || right <= left || totalRules > MAX_RULES) return
        val notesX = left + (right - left) * SCHEDULE_FRACTION
        val gutterX = left + GUTTER_PT
        val scheduleRules = rowRules * HOUR_LABELS.size
        header(canvas, "Date", left, y0, u)
        header(canvas, "Notes", notesX + LABEL_INSET_PT, y0, u)
        canvas.drawLine(left, y0, right, y0, paints.strong)
        for (n in 1..scheduleRules) {
            canvas.drawLine(left, y0 + n * u, notesX, y0 + n * u, if (n % rowRules == 0) paints.dark else paints.rule)
        }
        canvas.drawLine(gutterX, y0, gutterX, y0 + scheduleRules * u, paints.rule)
        paints.label.textSize = LABEL_SIZE * u
        for (hour in HOUR_LABELS.indices) {
            canvas.drawText(HOUR_LABELS[hour], left, y0 + (hour * rowRules + LABEL_BASELINE) * u, paints.label)
        }
        for (n in 1..totalRules) canvas.drawLine(notesX, y0 + n * u, right, y0 + n * u, paints.rule)
        canvas.drawLine(notesX, y0, notesX, y0 + totalRules * u, paints.strong)
    }

    /** Week header, 7 day boxes and a notes box in 2 columns, lined interiors. */
    fun plannerWeekly(
        canvas: Canvas,
        grid: GridUnit,
        widthPt: Float,
        heightPt: Float,
    ) {
        val u = grid.unitPt
        val y0 = grid.originYPt
        val left = grid.originXPt
        val right = widthPt - grid.originXPt
        val totalRules = rulesUntil(grid, heightPt - BOTTOM_MARGIN_PT)
        val boxRules = totalRules / BOX_ROWS
        if (boxRules < 2 || right <= left || totalRules > MAX_RULES) return
        val midX = (left + right) / 2f
        val boxesBottom = y0 + BOX_ROWS * boxRules * u
        header(canvas, "Week", left, y0, u)
        for (n in 1 until BOX_ROWS * boxRules) {
            if (n % boxRules != 0) canvas.drawLine(left, y0 + n * u, right, y0 + n * u, paints.rule)
        }
        for (row in 0..BOX_ROWS) canvas.drawLine(left, y0 + row * boxRules * u, right, y0 + row * boxRules * u, paints.strong)
        canvas.drawLine(left, y0, left, boxesBottom, paints.strong)
        canvas.drawLine(midX, y0, midX, boxesBottom, paints.strong)
        canvas.drawLine(right, y0, right, boxesBottom, paints.strong)
        paints.label.textSize = LABEL_SIZE * u
        for (i in DAY_LABELS.indices) {
            val x = (if (i % 2 == 0) left else midX) + LABEL_INSET_PT
            canvas.drawText(DAY_LABELS[i], x, y0 + ((i / 2) * boxRules + LABEL_BASELINE) * u, paints.label)
        }
    }

    private fun header(
        canvas: Canvas,
        text: String,
        xPt: Float,
        y0: Float,
        u: Float,
    ) {
        paints.label.textSize = HEADER_SIZE * u
        canvas.drawText(text, xPt, y0 - HEADER_LIFT * u, paints.label)
    }

    /** Index of the last rule at or above [yPt] (rule 0 = grid origin); negative if none. */
    private fun rulesUntil(
        grid: GridUnit,
        yPt: Float,
    ): Int = floor((yPt - grid.originYPt) / grid.unitPt + INDEX_EPS).toInt()

    private companion object {
        const val INDEX_EPS = 1e-3f // float error of rules that land exactly on a limit
        const val CUE_COLUMN_PT = 63.5f * PT_PER_MM
        const val SUMMARY_PT = 50.8f * PT_PER_MM
        const val STAVES_PER_A4 = 10f
        const val STAFF_LINES = 5
        const val STAFF_GAP_PT = 2f * PT_PER_MM
        const val STAFF_HEIGHT_PT = (STAFF_LINES - 1) * STAFF_GAP_PT
        const val MUSIC_BOTTOM_PT = 20f * PT_PER_MM
        const val MAX_STAVES = 200
        const val MAX_RULES = 2000
        const val GUTTER_PT = 15f * PT_PER_MM
        const val SCHEDULE_FRACTION = 0.6f
        const val BOX_ROWS = 4
        const val LABEL_INSET_PT = 2f * PT_PER_MM
        const val LABEL_SIZE = 0.4f
        const val LABEL_BASELINE = 0.7f
        const val HEADER_SIZE = 0.5f
        const val HEADER_LIFT = 0.35f
        const val FIRST_HOUR = 6
        const val LAST_HOUR = 22
        val HOUR_LABELS = Array(LAST_HOUR - FIRST_HOUR + 1) { "${(FIRST_HOUR + it).toString().padStart(2, '0')}:00" }
        val DAY_LABELS = arrayOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "Notes")
    }
}
