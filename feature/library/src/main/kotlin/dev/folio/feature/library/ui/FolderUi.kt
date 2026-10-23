package dev.folio.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolderColor
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.library.state.Crumb
import dev.folio.feature.library.state.FolderSummary
import dev.folio.feature.library.state.folderColor
import kotlinx.collections.immutable.ImmutableList

private val SWATCH = 44.dp
private const val SWATCHES_PER_ROW = 6
private val LIST_ROW_HEIGHT = 100.dp
private val LIST_THUMB = 100.dp
private val CRUMB_HEIGHT = 44.dp

/** Entries of the folder overflow menu. */
internal enum class FolderAction { Rename, Color, Move, Delete }

/** Overflow menu of a folder card: rename, color, move, delete to the bin. */
@Composable
internal fun FolderMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onAction: (FolderAction) -> Unit,
) {
    FolioMenu(expanded = expanded, onDismiss = onDismiss) {
        MenuRow("Rename", { onAction(FolderAction.Rename) }, icon = FolioIcons.Pencil)
        MenuRow("Color", { onAction(FolderAction.Color) }, icon = FolioIcons.Folder)
        MenuRow("Move", { onAction(FolderAction.Move) }, icon = FolioIcons.Folder)
        MenuRow("Delete", { onAction(FolderAction.Delete) }, icon = FolioIcons.Trash, destructive = true)
    }
}

/** Color dialog: the twelve folder colors, [current] marked; picking one confirms at once. */
@Composable
internal fun FolderColorDialog(
    current: FolderColor,
    onPick: (FolderColor) -> Unit,
    onDismiss: () -> Unit,
) {
    DialogFrame("Folder color", onDismiss) {
        FolderColor.entries.chunked(SWATCHES_PER_ROW).forEach { rowColors ->
            Row(horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s8)) {
                rowColors.forEach { color -> Swatch(color, selected = color == current, onClick = { onPick(color) }) }
            }
        }
        DialogButtons(confirmLabel = null, confirmEnabled = false, onConfirm = {}, onDismiss = onDismiss)
    }
}

@Composable
private fun Swatch(
    color: FolderColor,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val ring =
        if (selected) {
            Modifier.border(
                FolioTheme.space.selectedBorderWidth,
                FolioTheme.colors.accent,
                FolioTheme.shapes.full,
            )
        } else {
            Modifier
        }
    Box(
        Modifier
            .size(SWATCH)
            .clip(FolioTheme.shapes.full)
            .background(color.color)
            .then(ring)
            .clickable(role = Role.Button, onClickLabel = "Use ${color.name}", onClick = onClick)
            .semantics { contentDescription = color.name },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                FolioIcons.Check,
                contentDescription = null,
                tint = color.onColor,
                modifier = Modifier.size(FolioTheme.space.iconMenu),
            )
        }
    }
}

/**
 * Breadcrumb that replaces the tab chips inside a folder: a home button for the library root, then one entry per
 * folder on the way; the last one (the folder being shown) is in the accent color.
 */
@Composable
internal fun FolderBreadcrumb(
    crumbs: ImmutableList<Crumb>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    Row(modifier.heightIn(min = CRUMB_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        FolioIconButton(FolioIcons.House, "Library", { onOpen("") })
        crumbs.forEachIndexed { index, crumb ->
            val last = index == crumbs.lastIndex
            Icon(
                FolioIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(FolioTheme.space.s16),
                tint = colors.textTertiary,
            )
            Box(
                Modifier
                    .heightIn(min = CRUMB_HEIGHT)
                    .clip(FolioTheme.shapes.tile)
                    .clickable(enabled = !last, role = Role.Button, onClickLabel = "Open folder") { onOpen(crumb.path) }
                    .padding(horizontal = FolioTheme.space.s8),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = crumb.name,
                    style = FolioTheme.type.bodyMedium,
                    color = if (last) colors.accent else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A folder in the list view: a colored thumbnail, the note count, the name and the overflow button. */
@Composable
internal fun FolderListRow(
    summary: FolderSummary,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOverflow: () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val color = folderColor(summary.folder.tint)
    Row(
        Modifier
            .fillMaxWidth()
            .height(LIST_ROW_HEIGHT)
            .clip(FolioTheme.shapes.tile)
            .combinedClickable(onClickLabel = "Open folder", onLongClick = onLongClick, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(LIST_THUMB).clip(FolioTheme.shapes.tile).background(color.color),
            contentAlignment = Alignment.Center,
        ) {
            Icon(FolioIcons.Folder, contentDescription = null, modifier = Modifier.size(space.iconToolbar), tint = color.onColor)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space.s2)) {
            Text(
                text = if (summary.noteCount == 1) "1 note" else "${summary.noteCount} notes",
                style = FolioTheme.type.caption,
                color = colors.textTertiary,
            )
            Text(
                text = summary.folder.name,
                style = FolioTheme.type.bodyMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        FolioIconButton(FolioIcons.Ellipsis, contentDescription = "More", onClick = onOverflow, tint = colors.textTertiary)
    }
}
