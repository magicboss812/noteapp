package dev.folio.feature.editor.text

import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
import dev.folio.core.text.edit.BlockEditing
import dev.folio.core.text.edit.EditMarkers
import dev.folio.core.text.edit.TableCell
import dev.folio.core.text.edit.TableEditing
import dev.folio.core.text.flow.BlockPlacement
import dev.folio.core.text.layout.BlockFace
import dev.folio.core.text.layout.BlockLayout
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.TableAlign
import kotlinx.coroutines.flow.Flow

/**
 * The block the live text field edits (07-text-engine.md#editing): block [blockId] of flow [flowId], at [blockIndex] in
 * the flow. The field puts its caret at offset [caret] of the block source whenever [revision] changes (a tap, a
 * split or merge, a block the focus moved to); while the revision stays the field owns the caret.
 */
data class TextFocus(
    val flowId: FlowId,
    val blockId: Long,
    val blockIndex: Int,
    val caret: Int,
    val revision: Int,
)

/**
 * What the live field shows for [focus]: the [block] source in the face and size of its [layout], at [placement] (the
 * first line box of the block, page pt). In a table the field edits one [cell] only and shows its [text]. [dimmed]
 * lists the marker ranges of [text] as start/end pairs; [markerLength] is the length of the marker at the start of
 * [text] (a caret inside it counts as block start).
 */
class TextFieldSpec(
    val focus: TextFocus,
    val block: MarkdownBlock,
    val layout: BlockLayout,
    val placement: BlockPlacement,
    val cell: CellField? = CellField.of(block, layout, focus.caret),
    val dimmed: IntArray = EditMarkers.ranges(block).let { cell?.clip(it) ?: it },
    val markerLength: Int = if (cell == null) BlockEditing.markerLength(block) else 0,
) {
    /** Page the field sits on. */
    val pageId: PageId get() = placement.pageId

    /** What the field shows: the cell's Markdown in a table, the block source otherwise. */
    val text: String get() = cell?.text ?: block.source
}

/**
 * The table cell the live field edits (07-text-engine.md#tables-as-built): [cell] with Markdown [text], found at
 * [start] of the block source, laid out as cell [index] of the table. Geometry is pt from the block's top-left: the
 * text column at [leftPt] wide [widthPt], the row from [topPt] down [heightPt], the first baseline at [baselinePt].
 */
class CellField(
    val cell: TableCell,
    val text: String,
    val start: Int,
    val index: Int,
    val leftPt: Float,
    val widthPt: Float,
    val topPt: Float,
    val heightPt: Float,
    val baselinePt: Float,
    val align: TableAlign,
    val face: BlockFace?,
    /** The parser's message for invalid LaTeX in this cell, null when its math is fine. */
    val mathError: String?,
) {
    /** [ranges] of the block source (start/end pairs) cut to this cell and shifted to offsets of [text]. */
    fun clip(ranges: IntArray): IntArray {
        val end = start + text.length
        val out = ArrayList<Int>()
        for (i in 0 until ranges.size - 1 step 2) {
            val a = maxOf(ranges[i], start)
            val b = minOf(ranges[i + 1], end)
            if (a < b) {
                out += a - start
                out += b - start
            }
        }
        return out.toIntArray()
    }

    /** Constructor. */
    companion object {
        /** The cell of table [block] that holds source offset [caret]; null when [block] is not a laid-out table. */
        fun of(
            block: MarkdownBlock,
            layout: BlockLayout,
            caret: Int,
        ): CellField? {
            val table = layout.table
            if (block.content !is BlockContent.Table || table == null) return null
            val span = TableEditing.cellAt(block.source, caret)
            val row = span.cell.row.coerceIn(0, table.rows - 1)
            val column = span.cell.column.coerceIn(0, table.columns - 1)
            val index = table.cellIndex(row, column)
            val topPt = layout.lineTopPt(table.rowFirstLine(row))
            return CellField(
                cell = TableCell(row, column),
                text = block.source.substring(span.start, span.end),
                start = span.start,
                index = index,
                leftPt = table.textLeftPt(index),
                widthPt = table.textWidthPt(index),
                topPt = topPt,
                heightPt = layout.lineTopPt(table.rowLineCount(row)),
                baselinePt = topPt + table.cellBaselinePt(index, 0),
                align = table.aligns.getOrElse(column) { TableAlign.NONE },
                face = if (row == 0) table.headerFace else layout.face,
                mathError = layout.mathErrors.firstOrNull { it.cell == index }?.message,
            )
        }
    }
}

/**
 * Keys the live field leaves to the block structure instead of the text view (it sends them when they change blocks;
 * in a table at the edges of the cell, and Enter goes one row down).
 */
enum class FieldKey {
    /** Enter: splits the block at the caret, a selection is deleted first. */
    ENTER,

    /** Shift+Enter: a line break inside the block. */
    SOFT_ENTER,

    /** Backspace at the start of the block or inside its marker. */
    BACKSPACE,

    /** Delete at the end of the block. */
    DELETE,

    /** Left at the start of the block: the end of the block before. */
    LEFT,

    /** Right at the end of the block: the start of the block after. */
    RIGHT,

    /** Tab: a list item one level deeper; in a table the next cell. */
    TAB,

    /** Shift+Tab: a list item one level shallower; in a table the previous cell. */
    SHIFT_TAB,
}

/** A change the live field reports; the controller turns it into one edit of the focused block's flow. */
sealed interface FieldEdit {
    /** The field's text after a keystroke or a paste: the block source is now [source], the caret at [caret]. */
    data class Typed(
        val source: String,
        val caret: Int,
    ) : FieldEdit

    /** The cell field's text after a keystroke: table [cell] now holds [text], the caret at [caret] of it. */
    data class Cell(
        val cell: TableCell,
        val text: String,
        val caret: Int,
    ) : FieldEdit

    /**
     * A structural [key] with the selection [start] to [end] ([start] <= [end]): offsets of the block source, or of
     * the text of table [cell] when the field edits one.
     */
    data class Key(
        val key: FieldKey,
        val start: Int,
        val end: Int,
        val cell: TableCell? = null,
    ) : FieldEdit

    /** A formatting bar [action] on the selection [start] to [end] ([start] <= [end]; offsets as in [Key]). */
    data class Format(
        val action: FormatAction,
        val start: Int,
        val end: Int,
        val cell: TableCell? = null,
    ) : FieldEdit
}

/**
 * The formatting bar's actions (07-text-engine.md#editing): inline markers, block styles, a link and a table; in a
 * table the row, column and alignment actions of the table buttons.
 */
enum class FormatAction {
    BOLD,
    ITALIC,
    STRIKE,
    HIGHLIGHT,
    CODE,
    MATH,
    LINK,
    HEADING_1,
    HEADING_2,
    HEADING_3,
    BULLET,
    ORDERED,
    TASK,
    QUOTE,
    TABLE,
    TABLE_ROW_ADD,
    TABLE_ROW_REMOVE,
    TABLE_COLUMN_ADD,
    TABLE_COLUMN_REMOVE,
    TABLE_ALIGN_LEFT,
    TABLE_ALIGN_CENTER,
    TABLE_ALIGN_RIGHT,
}

/** What the canvas host asks of the text engine: focus a block by tapping it, the field to show for it, its edits. */
interface TextController {
    /** The live field to show, null while no block has focus or its place is not known. Emits on the `text` dispatcher. */
    val field: Flow<TextFieldSpec?>

    /**
     * Focuses the block shown at ([xPt], [yPt]) of page [pageId] with the caret under that point, or ends editing when
     * the point is on no text frame; true if a block got focus. Main thread, traced as `text:focus`.
     */
    fun focusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean

    /** Like [focusAt], but a point on no text frame leaves the focus where it is (arrow keys at the edge of the flow). */
    fun moveFocusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean

    /** Ends editing: the field goes away and the block is drawn by the page again. */
    fun blur()

    /**
     * Applies [edit] to the focused block. Edits run one after the other in the order given, a keystroke that is
     * still waiting is replaced by the next; the field is told the outcome through [field]. Main thread.
     */
    fun edit(edit: FieldEdit)

    /**
     * The live field's selection moved to [start]..[end] (any order): offsets of the block source, or of the text of
     * table [cell] when the field edits one. Main thread.
     */
    fun select(
        start: Int,
        end: Int,
        cell: TableCell? = null,
    )

    /** Applies the formatting bar [action] to the field's current selection, after the edits typed before it. Main thread. */
    fun format(action: FormatAction)
}
