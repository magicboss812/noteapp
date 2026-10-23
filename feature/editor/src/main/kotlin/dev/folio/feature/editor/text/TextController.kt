package dev.folio.feature.editor.text

import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
import dev.folio.core.text.edit.BlockEditing
import dev.folio.core.text.edit.EditMarkers
import dev.folio.core.text.flow.BlockPlacement
import dev.folio.core.text.layout.BlockLayout
import dev.folio.core.text.markdown.MarkdownBlock
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
 * first line box of the block, page pt). [dimmed] lists the marker ranges of the source as start/end pairs;
 * [markerLength] is the length of the marker at the start of the source (a caret inside it counts as block start).
 */
class TextFieldSpec(
    val focus: TextFocus,
    val block: MarkdownBlock,
    val layout: BlockLayout,
    val placement: BlockPlacement,
    val dimmed: IntArray = EditMarkers.ranges(block),
    val markerLength: Int = BlockEditing.markerLength(block),
) {
    /** Page the field sits on. */
    val pageId: PageId get() = placement.pageId
}

/** Keys the live field leaves to the block structure instead of the text view (it sends them when they change blocks). */
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

    /** Tab: a list item one level deeper. */
    TAB,

    /** Shift+Tab: a list item one level shallower. */
    SHIFT_TAB,
}

/** A change the live field reports; the controller turns it into one edit of the focused block's flow. */
sealed interface FieldEdit {
    /** The field's text after a keystroke or a paste: the block source is now [source], the caret at [caret]. */
    data class Typed(
        val source: String,
        val caret: Int,
    ) : FieldEdit

    /** A structural [key] with the selection [start] to [end] (both offsets of the block source, [start] <= [end]). */
    data class Key(
        val key: FieldKey,
        val start: Int,
        val end: Int,
    ) : FieldEdit

    /** A formatting bar [action] on the selection [start] to [end] of the block source ([start] <= [end]). */
    data class Format(
        val action: FormatAction,
        val start: Int,
        val end: Int,
    ) : FieldEdit
}

/** The formatting bar's actions (07-text-engine.md#editing): inline markers, block styles, a link and a table. */
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

    /** The live field's selection moved to [start]..[end] (offsets of the block source, any order). Main thread. */
    fun select(
        start: Int,
        end: Int,
    )

    /** Applies the formatting bar [action] to the field's current selection, after the edits typed before it. Main thread. */
    fun format(action: FormatAction)
}
