package dev.folio.feature.editor.text

import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
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
 * first line box of the block, page pt). [dimmed] lists the marker ranges of the source as start/end pairs.
 */
class TextFieldSpec(
    val focus: TextFocus,
    val block: MarkdownBlock,
    val layout: BlockLayout,
    val placement: BlockPlacement,
    val dimmed: IntArray = EditMarkers.ranges(block),
) {
    /** Page the field sits on. */
    val pageId: PageId get() = placement.pageId
}

/** What the canvas host asks of the text engine: focus a block by tapping it, and the field to show for it. */
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

    /** Ends editing: the field goes away and the block is drawn by the page again. */
    fun blur()
}
