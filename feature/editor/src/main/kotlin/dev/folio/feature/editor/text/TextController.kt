package dev.folio.feature.editor.text

import dev.folio.core.model.FlowId
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.edit.BlockEditing
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.EditMarkers
import dev.folio.core.text.edit.TableCell
import dev.folio.core.text.edit.TableEditing
import dev.folio.core.text.flow.BlockPlacement
import dev.folio.core.text.flow.CaretSpot
import dev.folio.core.text.flow.EditedBlock
import dev.folio.core.text.flow.PageText
import dev.folio.core.text.flow.SelectionBox
import dev.folio.core.text.flow.TapTarget
import dev.folio.core.text.layout.BlockFace
import dev.folio.core.text.layout.BlockLayout
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.TableAlign
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf

/**
 * The block the live text field edits (07-text-engine.md#editing): block [blockId] of flow [flowId], at [blockIndex] in
 * the flow. The field puts its caret at offset [caret] of the block source whenever [revision] changes (a tap, a
 * split or merge, a block the focus moved to); while the revision stays the field owns the caret. A selection has its
 * other end at [anchor] (07-text-engine.md#selection-as-built), in this block or in another block of the flow; null
 * for a plain caret.
 */
data class TextFocus(
    val flowId: FlowId,
    val blockId: Long,
    val blockIndex: Int,
    val caret: Int,
    val revision: Int,
    val anchor: Caret? = null,
) {
    /** True when the selection reaches into another block: the page highlights the rest, edits replace all of it. */
    val spansBlocks: Boolean get() = anchor != null && anchor.blockId != blockId
}

/**
 * What the live field shows for [focus]: the [block] source in the face and size of its [layout], at [placement] (the
 * first line box of the block, page pt). In a table the field edits one [cell] only and shows its [text]. [dimmed]
 * lists the marker ranges of [text] as start/end pairs; [markerLength] is the length of the marker at the start of
 * [text] (a caret inside it counts as block start). The field's selection runs from [anchorOffset] to the caret: the
 * anchor's offset in this block, the block start or end when the anchor lies in a block before or after. [edited] is
 * the block as the canvas overlay draws it (A-058); null in a table, where the field draws its cell itself.
 */
class TextFieldSpec(
    val focus: TextFocus,
    val block: MarkdownBlock,
    val layout: BlockLayout,
    val placement: BlockPlacement,
    val cell: CellField? = CellField.of(block, layout, focus.caret),
    val dimmed: IntArray = EditMarkers.ranges(block).let { cell?.clip(it) ?: it },
    val markerLength: Int = if (cell == null) BlockEditing.markerLength(block) else 0,
    val anchorOffset: Int = focus.anchor?.takeIf { it.blockId == block.id }?.offset ?: focus.caret,
    val edited: EditedBlock? = null,
) {
    /** Page the field sits on. */
    val pageId: PageId get() = placement.pageId

    /** What the field shows: the cell's Markdown in a table, the block source otherwise. */
    val text: String get() = cell?.text ?: block.source

    /** True when this spec edits the same block (and table cell) as [other]: the field stays, no handoff. */
    fun sameBlockAs(other: TextFieldSpec): Boolean =
        focus.flowId == other.focus.flowId && focus.blockId == other.focus.blockId && cell?.index == other.cell?.index
}

/**
 * What the canvas paints as text (A-042) together with the live [field] that draws the one block (or table cell) the
 * [pages] leave out. One value, so the host switches tiles and field from the same layout (P06-T18).
 */
class CanvasText(
    /** Page text by page; pages without text are absent. */
    val pages: Map<PageId, PageText>,
    /** The live field to show; null while no block has focus. */
    val field: TextFieldSpec?,
    /** The selection of [field]'s flow the canvas draws, null while it is collapsed. */
    val selection: SelectionHighlight? = null,
) {
    /** Constants. */
    companion object {
        /** No text, no field. */
        val EMPTY = CanvasText(emptyMap(), null)
    }
}

/**
 * A selection as the canvas draws it (07-text-engine.md#selection-as-built): the [boxes] of its lines outside the live
 * field (which shows its own part), and where its [anchor] and [focus] ends are for the drag handles (null when that
 * end is not placed on a page).
 */
class SelectionHighlight(
    val boxes: List<SelectionBox>,
    val anchor: CaretSpot?,
    val focus: CaretSpot?,
)

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

    /**
     * [text] replaces the selection (07-text-engine.md#selection-as-built): a paste, or typing and deleting over a
     * selection that spans blocks. Markdown with blank lines becomes several blocks; a [paste] that opens with a block
     * of its own after text starts a new block (BlockEditing.paste).
     */
    data class Replace(
        val text: String,
        val paste: Boolean = false,
    ) : FieldEdit

    /** The `[[` picker's choice: source [start] to [caret] (the typed `[[query`) becomes a link to note [docId] named [title]. */
    data class NoteLink(
        val start: Int,
        val caret: Int,
        val title: String,
        val docId: String,
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
    /** The live field to show, null while no block has focus or its place is not known (chrome state; the canvas uses [CanvasText]). */
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
     * The live field's selection moved to [start]..[end] (anchor, caret): offsets of the block source, or of the text
     * of table [cell] when the field edits one. Unless [extending] (Shift held, or the field showing the focus it was
     * given) a selection end in another block is dropped. Main thread.
     */
    fun select(
        start: Int,
        end: Int,
        cell: TableCell? = null,
        extending: Boolean = false,
    )

    /**
     * Shift+Left ([forward] false) at the start or Shift+Right at the end of the field: the selection's caret end goes
     * into the block before or after. Main thread.
     */
    fun extendHorizontally(forward: Boolean) = Unit

    /**
     * Shift+Up or Shift+Down past the field's first or last line, or a selection handle dragged: the caret end of the
     * selection goes to the text at ([xPt], [yPt]) of page [pageId] in the same flow. Main thread.
     */
    fun extendTo(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ) = Unit

    /**
     * A selection handle dragged to ([xPt], [yPt]) of page [pageId]: the [anchor] end (the start handle of a forward
     * selection) or the caret end moves there. Main thread.
     */
    fun dragSelection(
        anchor: Boolean,
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ) = if (anchor) Unit else extendTo(pageId, xPt, yPt)

    /**
     * A long-press or double tap at ([xPt], [yPt]) of page [pageId]: selects the word there (`WordRanges`) if the
     * point is on the block being edited; true if it was (the gesture then belongs to the selection). Main thread.
     */
    fun selectWordAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean = false

    /**
     * The finger of a long-press moved to ([xPt], [yPt]) of page [pageId]: the selection runs from the long-pressed
     * word to the edge of the word there, across blocks and pages of the flow. Main thread.
     */
    fun extendWordsTo(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ) = Unit

    /** Ctrl+A: the focused block, then the whole flow. Main thread. */
    fun selectAll() = Unit

    /**
     * The focused block with [source] typed into it, laid out and paginated from where it starts, for the overlay to
     * draw before the flow reflows (A-058); null when nothing is edited. Main thread.
     */
    fun retype(source: String): EditedBlock? = null

    /** The selected Markdown, as in the flow (block markers and blank lines included); null for none. Main thread. */
    fun copySelection(): String? = null

    /** Like [copySelection], and the selection is deleted as one undo step. Main thread. */
    fun cutSelection(): String? = null

    /** Applies the formatting bar [action] to the field's current selection, after the edits typed before it. Main thread. */
    fun format(action: FormatAction)

    /**
     * Acts on the checkbox or link at ([xPt], [yPt]) of page [pageId] in a block that is not being edited
     * (07-text-engine.md#block-styles-as-built): a checkbox toggles, a link goes to [linkTaps]. True if there was one
     * (the tap then does not focus). Main thread.
     */
    fun tapAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean = false

    /** Web and note links tapped on the page, for the screen to open. */
    val linkTaps: Flow<TapTarget> get() = emptyFlow()

    /** The query of a `[[` note link typed at the caret of the focused block, null while the caret is in none. */
    val noteQuery: Flow<String?> get() = flowOf(null)

    /** Replaces the typed `[[query` with a link to note [docId] named [title] (the `[[` picker's choice). Main thread. */
    fun insertNoteLink(
        title: String,
        docId: String,
    ) = Unit

    /**
     * A Text tool tap at ([xPt], [yPt]) of page [pageId] (10-editor-ui.md#text-tool): the caret goes to the text there,
     * to the tapped line below the end of a flow, into the body flow of the page or into a new text box. Main thread.
     */
    fun placeCaret(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ) = Unit

    /**
     * The grip or a width handle of free text box [frameId] on page [fromPage] let go (P06-T17): the box goes to
     * [rect] (already snapped to the grid) of page [toPage] and reflows, as one undo step; the focus stays. Main thread.
     */
    fun placeBox(
        frameId: ObjectId,
        fromPage: PageId,
        toPage: PageId,
        rect: RectPt,
    ) = Unit

    /**
     * Hardware keyboard [text] typed while no block has focus: it goes to the end of page [pageId]'s body text (the
     * body flow is created when the page has none). False if the page has no body zone. Main thread.
     */
    fun typeIntoPage(
        pageId: PageId,
        text: String,
    ): Boolean = false
}
