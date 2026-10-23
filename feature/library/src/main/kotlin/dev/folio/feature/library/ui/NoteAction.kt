package dev.folio.feature.library.ui

import androidx.compose.runtime.Composable
import dev.folio.core.designsystem.icon.FolioIcons

/** Entries of the note overflow menu that act today. */
internal enum class NoteAction { Rename, Move, Duplicate, ToggleFavorite, Tags, Delete }

/**
 * Overflow menu of a note card: rename, move, duplicate, favorite, tags, export, delete. Export is listed disabled
 * until its screen exists (P09).
 */
@Composable
internal fun NoteMenu(
    expanded: Boolean,
    favorite: Boolean,
    onDismiss: () -> Unit,
    onAction: (NoteAction) -> Unit,
) {
    FolioMenu(expanded = expanded, onDismiss = onDismiss) {
        MenuRow("Rename", { onAction(NoteAction.Rename) }, icon = FolioIcons.Pencil)
        MenuRow("Move", { onAction(NoteAction.Move) }, icon = FolioIcons.Folder)
        MenuRow("Duplicate", { onAction(NoteAction.Duplicate) }, icon = FolioIcons.FilePlus)
        MenuRow(
            label = if (favorite) "Remove favorite" else "Favorite",
            onClick = { onAction(NoteAction.ToggleFavorite) },
            icon = FolioIcons.Star,
        )
        MenuRow("Tags", { onAction(NoteAction.Tags) }, icon = FolioIcons.Tag)
        MenuRow("Export", {}, icon = FolioIcons.Share2, enabled = false)
        MenuRow("Delete", { onAction(NoteAction.Delete) }, icon = FolioIcons.Trash, destructive = true)
    }
}
