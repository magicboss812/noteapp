package dev.folio.feature.editor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.PillDivider
import dev.folio.core.designsystem.component.PillGroup
import dev.folio.core.designsystem.component.PillOrientation
import dev.folio.core.designsystem.component.ToolButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.text.edit.BlockStyle
import dev.folio.feature.editor.text.FormatAction

private class FormatButton(
    val action: FormatAction,
    val icon: ImageVector,
    val label: String,
    val style: BlockStyle? = null,
)

// Block styles: selected while the focused block has that style; tapping it again makes a paragraph.
private val STYLE_BUTTONS =
    listOf(
        FormatButton(FormatAction.HEADING_1, FolioIcons.Heading1, "Heading 1", BlockStyle.HEADING_1),
        FormatButton(FormatAction.HEADING_2, FolioIcons.Heading2, "Heading 2", BlockStyle.HEADING_2),
        FormatButton(FormatAction.HEADING_3, FolioIcons.Heading3, "Heading 3", BlockStyle.HEADING_3),
        FormatButton(FormatAction.BULLET, FolioIcons.List, "Bullet list", BlockStyle.BULLET),
        FormatButton(FormatAction.ORDERED, FolioIcons.ListOrdered, "Numbered list", BlockStyle.ORDERED),
        FormatButton(FormatAction.TASK, FolioIcons.ListChecks, "Checklist", BlockStyle.TASK),
        FormatButton(FormatAction.QUOTE, FolioIcons.Quote, "Quote", BlockStyle.QUOTE),
    )

// Inline markers around the selection (or an empty pair at the caret), then a link.
private val INLINE_BUTTONS =
    listOf(
        FormatButton(FormatAction.BOLD, FolioIcons.Bold, "Bold"),
        FormatButton(FormatAction.ITALIC, FolioIcons.Italic, "Italic"),
        FormatButton(FormatAction.STRIKE, FolioIcons.Strikethrough, "Strikethrough"),
        FormatButton(FormatAction.HIGHLIGHT, FolioIcons.Highlighter, "Highlight"),
        FormatButton(FormatAction.CODE, FolioIcons.Code, "Code"),
        FormatButton(FormatAction.MATH, FolioIcons.Sigma, "Math"),
        FormatButton(FormatAction.LINK, FolioIcons.Link, "Link"),
    )

// In a table: rows and columns at the caret's cell, then the caret column's alignment (again: back to none).
private val TABLE_BUTTONS =
    listOf(
        FormatButton(FormatAction.TABLE_ROW_ADD, FolioIcons.BetweenHorizontalEnd, "Add row below"),
        FormatButton(FormatAction.TABLE_ROW_REMOVE, FolioIcons.FoldVertical, "Remove row"),
        FormatButton(FormatAction.TABLE_COLUMN_ADD, FolioIcons.BetweenVerticalEnd, "Add column right"),
        FormatButton(FormatAction.TABLE_COLUMN_REMOVE, FolioIcons.FoldHorizontal, "Remove column"),
    )

private val ALIGN_BUTTONS =
    listOf(
        FormatButton(FormatAction.TABLE_ALIGN_LEFT, FolioIcons.TextAlignStart, "Align column left"),
        FormatButton(FormatAction.TABLE_ALIGN_CENTER, FolioIcons.TextAlignCenter, "Center column"),
        FormatButton(FormatAction.TABLE_ALIGN_RIGHT, FolioIcons.TextAlignEnd, "Align column right"),
    )

/**
 * The formatting bar (07-text-engine.md#editing): row 2 while a text block is focused, whatever the tool (A-045).
 * A bottom-docked toolbox slides above the on-screen keyboard, so the bar sits above it; with a hardware keyboard
 * it stays at the options row. [style] is the focused block's style; undo and redo stay in the edit pill. In a
 * [table] the block styles and the table button give way to the table's row, column and alignment buttons.
 */
@Composable
internal fun TextFormatRow(
    style: BlockStyle,
    onFormat: (FormatAction) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    table: Boolean = false,
) {
    val orientation = if (vertical) PillOrientation.Vertical else PillOrientation.Horizontal
    PillGroup(modifier, orientation = orientation) {
        val scroll = rememberScrollState()
        val content: @Composable () -> Unit = {
            if (table) {
                TABLE_BUTTONS.forEach { b -> FolioIconButton(b.icon, b.label, onClick = { onFormat(b.action) }) }
                PillDivider(vertical = vertical)
                ALIGN_BUTTONS.forEach { b -> FolioIconButton(b.icon, b.label, onClick = { onFormat(b.action) }) }
            } else {
                STYLE_BUTTONS.forEach { b -> ToolButton(b.icon, b.label, selected = style == b.style, onClick = { onFormat(b.action) }) }
            }
            PillDivider(vertical = vertical)
            INLINE_BUTTONS.forEach { b -> FolioIconButton(b.icon, b.label, onClick = { onFormat(b.action) }) }
            if (!table) {
                PillDivider(vertical = vertical)
                FolioIconButton(FolioIcons.Table, "Table", onClick = { onFormat(FormatAction.TABLE) })
            }
        }
        if (vertical) {
            Column(
                Modifier.edgeFade(scroll, SCROLL_FADE, vertical = true).verticalScroll(scroll),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        } else {
            Row(Modifier.edgeFade(scroll, SCROLL_FADE).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                content()
            }
        }
    }
}
