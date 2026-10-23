package dev.folio.feature.editor.text

import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
import dev.folio.core.text.flow.FlowLayout
import dev.folio.core.text.flow.hit
import dev.folio.core.text.flow.placementOf
import dev.folio.core.text.flow.sourceOffsetAt

/**
 * The focus after the flows were laid out again as [layouts]: unchanged while its block exists (with the block's new
 * place), moved to the block at the same place when undo or redo parsed the text again and gave every block a new id,
 * null when the flow has no frames any more.
 */
internal fun TextFocus.resolvedIn(layouts: Map<FlowId, FlowLayout>): TextFocus? {
    val blocks = layouts[flowId]?.markdown?.blocks
    val index = blocks?.indexOfFirst { it.id == blockId } ?: -1
    return when {
        blocks.isNullOrEmpty() -> {
            null
        }

        index >= 0 -> {
            copy(blockIndex = index)
        }

        else -> {
            val at = blockIndex.coerceIn(0, blocks.lastIndex)
            TextFocus(flowId, blocks[at].id, at, blocks[at].source.length, revision + 1)
        }
    }
}

/**
 * The focus a tap at ([xPt], [yPt]) of page [pageId] means: the block under the point in the first flow with a frame
 * there, the caret at the character nearest to it (the end of the block below the last line). Null on no text frame.
 * [revision] is the revision of the focus before; the new one is the next.
 */
internal fun TextSnapshot.focusAt(
    pageId: PageId,
    xPt: Float,
    yPt: Float,
    revision: Int,
): TextFocus? =
    layouts.firstNotNullOfOrNull { (flowId, layout) ->
        val hit = layout.hit(pageId, xPt, yPt)
        val block = hit?.let { layout.markdown.blocks.getOrNull(it.blockIndex) }
        if (hit == null || block == null) {
            null
        } else {
            TextFocus(flowId, block.id, hit.blockIndex, layout.sourceOffsetAt(hit, xPt), revision + 1)
        }
    }

/** The live field of [focus] in this snapshot; null for no focus and until the block is placed. */
internal fun TextSnapshot.fieldOf(focus: TextFocus?): TextFieldSpec? {
    if (focus == null) return null
    val layout = layouts[focus.flowId] ?: return null
    val index = layout.markdown.indexOf(focus.blockId)
    val block = layout.markdown.blocks.getOrNull(index)
    val blockLayout = layout.layoutOf(index)
    val placement = layout.placementOf(index)
    return if (block == null || blockLayout == null || placement == null) null else TextFieldSpec(focus, block, blockLayout, placement)
}
