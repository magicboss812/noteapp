package dev.folio.core.text.edit

import dev.folio.core.text.edit.BlockSplice.Range
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.core.text.markdown.TableAlign

/** A cell of a table block: [row] 0 is the header, body rows count from 1; [column] from 0. */
data class TableCell(
    val row: Int,
    val column: Int,
)

/** Where the text of [cell] is in a table block's source: [start] until [end] (spaces around it left out). */
data class CellSpan(
    val cell: TableCell,
    val start: Int,
    val end: Int,
)

/**
 * Editing a table block cell by cell (07-text-engine.md#tables). The live field edits one cell's Markdown; every
 * change here writes the table back as normalized GFM (a space and padding around every cell, the delimiter row
 * spelling the column alignment) and puts the caret into the cell it lands in. All functions return null when the
 * caret is not in a table block or the move has nothing to do.
 */
@Suppress("TooManyFunctions") // one entry point per table action
object TableEditing {
    /** The cell of the table [source] that holds offset [offset]; the delimiter row stands for the header. */
    fun cellAt(
        source: String,
        offset: Int,
    ): CellSpan = TableSource.parse(source).spanAt(offset.coerceIn(0, source.length))

    /** Where the text of [cell] is in the table [source] (clamped to the table). */
    fun spanOf(
        source: String,
        cell: TableCell,
    ): CellSpan = TableSource.parse(source).span(cell)

    /**
     * Sets the Markdown of the caret's cell to [text] (the cell field's text) with the caret [caretInCell] into it.
     * Line breaks become spaces and a bare `|` is escaped, so the text stays in its cell.
     */
    fun setCell(
        doc: MarkdownDocument,
        caret: Caret,
        text: String,
        caretInCell: Int,
    ): EditResult? =
        edit(doc, caret) { grid, at ->
            val (clean, cleanCaret) = cellText(text, caretInCell.coerceIn(0, text.length))
            grid.rows[at.row][at.column] = clean
            Target(at.row, at.column, cleanCaret)
        }

    /** Tab: the start of the next cell; past the last cell a new row is added. */
    fun nextCell(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult? =
        edit(doc, caret) { grid, at ->
            when {
                at.column < grid.columns - 1 -> Target(at.row, at.column + 1, 0)
                at.row < grid.rows.size - 1 -> Target(at.row + 1, 0, 0)
                else -> Target(grid.addRow(grid.rows.size), 0, 0)
            }
        }

    /** Shift+Tab: the start of the previous cell; null in the first cell. */
    fun previousCell(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult? =
        edit(doc, caret) { grid, at ->
            when {
                at.column > 0 -> Target(at.row, at.column - 1, 0)
                at.row > 0 -> Target(at.row - 1, grid.columns - 1, 0)
                else -> null
            }
        }

    /**
     * Enter: the same column one row down. In the last row a new row is added, unless that row is empty and not the
     * only body row: then the table ends there and an empty paragraph below gets the caret.
     */
    fun enter(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult? {
        val index = doc.indexOf(caret.blockId)
        val block = doc.blocks.getOrNull(index)?.takeIf { it.content is BlockContent.Table } ?: return null
        val grid = TableGrid.parse(block.source)
        val at = TableSource.parse(block.source).spanAt(caret.offset.coerceIn(0, block.source.length)).cell
        val last = grid.rows.size - 1
        if (at.row == last && last >= 2 && grid.rows[last].all { it.isBlank() }) {
            grid.rows.removeAt(last)
            val kept = grid.serialize()
            return BlockSplice.replace(doc, Range.block(index), "$kept\n\n", kept.length + 2, gap = "\n")
        }
        val row = if (at.row < last) at.row + 1 else grid.addRow(last + 1)
        return write(doc, caret.blockId, grid, Target(row, at.column, 0))
    }

    /** Left at the start of a cell: the end of the previous cell, or out of the table before its first cell. */
    fun left(
        doc: MarkdownDocument,
        caret: Caret,
    ): Caret? {
        val block = tableBlock(doc, caret) ?: return null
        val table = TableSource.parse(block.source)
        val at = table.spanAt(caret.offset.coerceIn(0, block.source.length)).cell
        val previous =
            when {
                at.column > 0 -> TableCell(at.row, at.column - 1)
                at.row > 0 -> TableCell(at.row - 1, table.columns - 1)
                else -> return BlockEditing.left(doc, Caret(caret.blockId, 0))
            }
        return Caret(caret.blockId, table.span(previous).end)
    }

    /** Right at the end of a cell: the start of the next cell, or out of the table after its last cell. */
    fun right(
        doc: MarkdownDocument,
        caret: Caret,
    ): Caret? {
        val block = tableBlock(doc, caret) ?: return null
        val table = TableSource.parse(block.source)
        val at = table.spanAt(caret.offset.coerceIn(0, block.source.length)).cell
        val next =
            when {
                at.column < table.columns - 1 -> TableCell(at.row, at.column + 1)
                at.row < table.rows - 1 -> TableCell(at.row + 1, 0)
                else -> return BlockEditing.right(doc, Caret(caret.blockId, block.source.length))
            }
        return Caret(caret.blockId, table.span(next).start)
    }

    /** An empty row below the caret's row (the first body row when the caret is in the header); the caret goes there. */
    fun addRow(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult? = edit(doc, caret) { grid, at -> Target(grid.addRow(at.row + 1), at.column, 0) }

    /** Removes the caret's body row (the header stays); the caret goes to the same column of the row now there. */
    fun removeRow(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult? =
        edit(doc, caret) { grid, at ->
            if (at.row == 0) return@edit null
            grid.rows.removeAt(at.row)
            Target(minOf(at.row, grid.rows.size - 1), at.column, 0)
        }

    /** An empty column right of the caret's column; the caret goes into it. */
    fun addColumn(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult? =
        edit(doc, caret) { grid, at ->
            val column = at.column + 1
            grid.aligns.add(column, TableAlign.NONE)
            for (row in grid.rows) row.add(column, "")
            Target(at.row, column, 0)
        }

    /** Removes the caret's column; removing the only column removes the table and leaves an empty paragraph. */
    fun removeColumn(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult? {
        val index = doc.indexOf(caret.blockId)
        val block = tableBlock(doc, caret) ?: return null
        val grid = TableGrid.parse(block.source)
        if (grid.columns == 1) return BlockSplice.replace(doc, Range.block(index), "", 0)
        val at = TableSource.parse(block.source).spanAt(caret.offset.coerceIn(0, block.source.length)).cell
        grid.aligns.removeAt(at.column)
        for (row in grid.rows) row.removeAt(at.column)
        return write(doc, caret.blockId, grid, Target(at.row, minOf(at.column, grid.columns - 1), 0))
    }

    /** Sets the caret's column to [align]; the alignment it already has goes back to none. The caret stays. */
    fun align(
        doc: MarkdownDocument,
        caret: Caret,
        align: TableAlign,
    ): EditResult? =
        edit(doc, caret) { grid, at ->
            grid.aligns[at.column] = if (grid.aligns[at.column] == align) TableAlign.NONE else align
            Target(at.row, at.column, at.inCell)
        }

    /** The caret's place in a table: its cell and how far into the cell text. */
    private class At(
        val row: Int,
        val column: Int,
        val inCell: Int,
    )

    /** Where the caret goes after an edit. */
    private class Target(
        val row: Int,
        val column: Int,
        val inCell: Int,
    )

    private fun tableBlock(
        doc: MarkdownDocument,
        caret: Caret,
    ) = doc.blocks.getOrNull(doc.indexOf(caret.blockId))?.takeIf { it.content is BlockContent.Table }

    private inline fun edit(
        doc: MarkdownDocument,
        caret: Caret,
        change: (TableGrid, At) -> Target?,
    ): EditResult? {
        val block = tableBlock(doc, caret) ?: return null
        val span = TableSource.parse(block.source).spanAt(caret.offset.coerceIn(0, block.source.length))
        val grid = TableGrid.parse(block.source)
        val inCell = (caret.offset - span.start).coerceIn(0, span.end - span.start)
        val target = change(grid, At(span.cell.row, span.cell.column, inCell)) ?: return null
        return write(doc, caret.blockId, grid, target)
    }

    private fun write(
        doc: MarkdownDocument,
        blockId: Long,
        grid: TableGrid,
        target: Target,
    ): EditResult {
        val source = grid.serialize()
        val text = grid.rows[target.row][target.column]
        val offset = grid.cellStart(target.row, target.column) + target.inCell.coerceIn(0, text.length)
        return BlockSplice.replaceSourceAt(doc, blockId, source, offset)
    }

    // Line breaks become spaces and every unescaped `|` gets its backslash; the caret moves with the inserted ones.
    private fun cellText(
        text: String,
        caret: Int,
    ): Pair<String, Int> {
        val out = StringBuilder(text.length + 2)
        var shifted = caret
        for ((i, c) in text.withIndex()) {
            when {
                c == '\n' || c == '\r' -> {
                    out.append(' ')
                }

                c == '|' && !TableSource.isEscaped(text, i) -> {
                    out.append('\\').append('|')
                    if (i < caret) shifted++
                }

                else -> {
                    out.append(c)
                }
            }
        }
        return out.toString() to shifted
    }
}

/**
 * The cells of a table block as Markdown strings, row 0 the header, every row [columns] long, for rewriting the
 * table as normalized GFM.
 */
internal class TableGrid(
    val aligns: MutableList<TableAlign>,
    val rows: MutableList<MutableList<String>>,
) {
    val columns: Int get() = aligns.size

    /** Inserts an empty row at [index] (at least 1: below the header) and returns its index. */
    fun addRow(index: Int): Int {
        val at = index.coerceIn(1, rows.size)
        rows.add(at, MutableList(columns) { "" })
        return at
    }

    private fun widths(): IntArray = IntArray(columns) { c -> maxOf(MIN_DASHES, rows.maxOf { it[c].length }) }

    /** The table as GFM: `| cell | cell |` rows padded to the widest cell of each column, the delimiter row second. */
    fun serialize(): String {
        val widths = widths()
        return buildString {
            for ((r, row) in rows.withIndex()) {
                if (r > 0) append('\n')
                append('|')
                for (c in 0 until columns) append(' ').append(row[c].padEnd(widths[c])).append(" |")
                if (r == 0) {
                    append("\n|")
                    for (c in 0 until columns) append(' ').append(delimiter(aligns[c], widths[c])).append(" |")
                }
            }
        }
    }

    /** Offset in [serialize] where the text of the cell in [row] and [column] starts. */
    fun cellStart(
        row: Int,
        column: Int,
    ): Int {
        val widths = widths()
        val lineLength = 1 + widths.sumOf { it + CELL_FRAME }
        // Header, delimiter, then the body rows; every line ends with a line break.
        val line = if (row == 0) 0 else row + 1
        var offset = line * (lineLength + 1) + 2
        for (c in 0 until column) offset += widths[c] + CELL_FRAME
        return offset
    }

    companion object {
        private const val MIN_DASHES = 3
        private const val CELL_FRAME = 3 // " " before, " |" after

        fun parse(source: String): TableGrid {
            val table = TableSource.parse(source)
            val aligns = MutableList(table.columns) { table.aligns.getOrElse(it) { TableAlign.NONE } }
            val rows =
                MutableList(table.rows) { r ->
                    MutableList(table.columns) { c ->
                        val span = table.span(TableCell(r, c))
                        source.substring(span.start, span.end)
                    }
                }
            return TableGrid(aligns, rows)
        }

        private fun delimiter(
            align: TableAlign,
            width: Int,
        ): String =
            when (align) {
                TableAlign.NONE -> "-".repeat(width)
                TableAlign.LEFT -> ":" + "-".repeat(width - 1)
                TableAlign.RIGHT -> "-".repeat(width - 1) + ":"
                TableAlign.CENTER -> ":" + "-".repeat(width - 2) + ":"
            }
    }
}

/**
 * Cell positions in the source of a table block: lines split at unescaped `|`, a leading and a trailing pipe
 * optional. Row 0 is line 0 (the header), line 1 the delimiter row, body row r line r + 1. The column count is the
 * header's; a row with fewer cells gets empty ones at its end, extra cells are ignored.
 */
internal class TableSource private constructor(
    private val source: String,
    // Per table row: the line's start and end, then content start and end of each of its cells.
    private val lines: List<IntArray>,
    private val cells: List<IntArray>,
    val aligns: List<TableAlign>,
) {
    val columns: Int get() = maxOf(1, cells.first().size / 2)

    val rows: Int get() = cells.size

    /** Text span of [cell], clamped to the table; a missing cell is empty at the end of its row. */
    fun span(cell: TableCell): CellSpan {
        val row = cell.row.coerceIn(0, rows - 1)
        val column = cell.column.coerceIn(0, columns - 1)
        val c = cells[row]
        if (column * 2 + 1 >= c.size) {
            val end = lines[row][1]
            return CellSpan(TableCell(row, column), end, end)
        }
        return CellSpan(TableCell(row, column), c[column * 2], c[column * 2 + 1])
    }

    /** The cell whose segment (from the pipe before it up to the pipe after it) holds [offset]. */
    fun spanAt(offset: Int): CellSpan {
        val lineIndex = source.substring(0, offset).count { it == '\n' }
        val row = if (lineIndex <= 1) 0 else (lineIndex - 1).coerceAtMost(rows - 1)
        val pipes = pipesOf(lineIndex)
        // The cell after the last pipe at or before the offset (the leading pipe opens cell 0).
        var column = 0
        val leading = pipes.isNotEmpty() && source.substring(lineStart(lineIndex), pipes[0]).isBlank()
        for (p in pipes.indices) if (pipes[p] < offset) column = if (leading) p else p + 1
        return span(TableCell(row, column))
    }

    private fun lineStart(lineIndex: Int): Int {
        var start = 0
        repeat(lineIndex) { start = source.indexOf('\n', start) + 1 }
        return start
    }

    private fun pipesOf(lineIndex: Int): IntArray {
        val start = lineStart(lineIndex)
        val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
        return pipes(source, start, end)
    }

    companion object {
        fun parse(source: String): TableSource {
            val lineRanges = ArrayList<IntArray>()
            var start = 0
            while (true) {
                val end = source.indexOf('\n', start).let { if (it < 0) source.length else it }
                lineRanges += intArrayOf(start, end)
                if (end >= source.length) break
                start = end + 1
            }
            val rowLines = lineRanges.filterIndexed { i, _ -> i != 1 }
            val cells = rowLines.map { cellsOf(source, it[0], it[1]) }
            val aligns =
                lineRanges.getOrNull(1)?.let { d ->
                    val c = cellsOf(source, d[0], d[1])
                    List(c.size / 2) { alignOf(source.substring(c[it * 2], c[it * 2 + 1])) }
                } ?: emptyList()
            val columns = maxOf(1, cells.first().size / 2)
            return TableSource(source, rowLines, cells, List(columns) { aligns.getOrElse(it) { TableAlign.NONE } })
        }

        /** True when the character at [index] is preceded by an odd number of backslashes. */
        fun isEscaped(
            text: CharSequence,
            index: Int,
        ): Boolean {
            var slashes = 0
            var i = index - 1
            while (i >= 0 && text[i] == '\\') {
                slashes++
                i--
            }
            return slashes % 2 == 1
        }

        private fun pipes(
            source: String,
            start: Int,
            end: Int,
        ): IntArray {
            var count = 0
            for (i in start until end) if (source[i] == '|' && !isEscaped(source, i)) count++
            val out = IntArray(count)
            var n = 0
            for (i in start until end) if (source[i] == '|' && !isEscaped(source, i)) out[n++] = i
            return out
        }

        // Content start and end of every cell of the line [start, end), spaces around the content left out. An empty
        // cell sits one space after its pipe (where a caret goes in `|  |`).
        private fun cellsOf(
            source: String,
            start: Int,
            end: Int,
        ): IntArray {
            val pipes = pipes(source, start, end)
            val bounds = ArrayList<Int>(pipes.size + 2)
            val leading = pipes.isNotEmpty() && source.substring(start, pipes[0]).isBlank()
            if (!leading) bounds += start - 1
            for (p in pipes) bounds += p
            val trailing = pipes.isNotEmpty() && source.substring(pipes.last() + 1, end).isBlank()
            if (!trailing) bounds += end
            val out = IntArray(maxOf(0, bounds.size - 1) * 2)
            for (k in 0 until bounds.size - 1) {
                val segStart = bounds[k] + 1
                val segEnd = bounds[k + 1]
                var a = segStart
                var b = segEnd
                while (a < b && source[a] == ' ') a++
                while (b > a && source[b - 1] == ' ') b--
                if (a == b) {
                    a = minOf(segStart + 1, segEnd)
                    b = a
                }
                out[k * 2] = a
                out[k * 2 + 1] = b
            }
            return out
        }

        private fun alignOf(cell: String): TableAlign {
            val left = cell.startsWith(":")
            val right = cell.endsWith(":") && cell.length > 1
            return when {
                left && right -> TableAlign.CENTER
                left -> TableAlign.LEFT
                right -> TableAlign.RIGHT
                else -> TableAlign.NONE
            }
        }
    }
}
