package dev.folio.feature.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.library.state.LibraryFolderItem
import kotlinx.collections.immutable.ImmutableList

private val MOVE_LIST_MAX = 320.dp
private val FOLDER_INDENT = 20.dp

/** Rename dialog: one text field with the current [title]; confirm is enabled while the text is not blank. */
@Composable
internal fun RenameNoteDialog(
    title: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FolioTheme.colors
    var text by rememberSaveable { mutableStateOf(title) }
    DialogFrame("Rename note", onDismiss) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = FolioTheme.type.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .background(colors.surfaceInset, FolioTheme.shapes.field)
                    .border(BorderStroke(FolioTheme.space.borderWidth, colors.border), FolioTheme.shapes.field)
                    .padding(horizontal = FolioTheme.space.s12)
                    .semantics { contentDescription = "Note title" },
            decorationBox = { inner ->
                Box(Modifier.heightIn(min = FolioTheme.space.touchTarget), contentAlignment = Alignment.CenterStart) { inner() }
            },
        )
        DialogButtons(
            confirmLabel = "Rename",
            confirmEnabled = text.isNotBlank(),
            onConfirm = { onRename(text.trim()) },
            onDismiss = onDismiss,
        )
    }
}

/** Move dialog: the library root and every folder of [folders]; [currentFolder] is marked and cannot be picked. */
@Composable
internal fun MoveNotesDialog(
    folders: ImmutableList<LibraryFolderItem>,
    currentFolder: String?,
    onMove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = listOf(LibraryFolderItem("", "Library", -1)) + folders
    DialogFrame("Move to", onDismiss) {
        LazyColumn(Modifier.heightIn(max = MOVE_LIST_MAX)) {
            items(targets, key = { it.path }) { target ->
                FolderRow(target, enabled = target.path != currentFolder, onClick = { onMove(target.path) })
            }
        }
        DialogButtons(confirmLabel = null, confirmEnabled = false, onConfirm = {}, onDismiss = onDismiss)
    }
}

@Composable
private fun FolderRow(
    folder: LibraryFolderItem,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = FolioTheme.colors
    val tint = if (enabled) colors.textPrimary else colors.textDisabled
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FolioTheme.space.touchTarget)
            .clip(FolioTheme.shapes.tile)
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Move here", onClick = onClick)
            .padding(start = FOLDER_INDENT * (folder.depth + 1).coerceAtLeast(0), end = FolioTheme.space.s12),
        horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(FolioIcons.Folder, contentDescription = null, modifier = Modifier.size(FolioTheme.space.iconMenu), tint = tint)
        Text(text = folder.name, style = FolioTheme.type.body, color = tint, maxLines = 1)
    }
}

@Composable
private fun DialogFrame(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .widthIn(max = space.dialogWidth)
                .background(colors.surfaceDialog, FolioTheme.shapes.card)
                .padding(start = space.s24, end = space.s12, top = space.s24, bottom = space.s8),
            verticalArrangement = Arrangement.spacedBy(space.s12),
        ) {
            Text(text = title, style = FolioTheme.type.title, color = colors.textPrimary, modifier = Modifier.padding(end = space.s12))
            Column(Modifier.padding(end = space.s12), verticalArrangement = Arrangement.spacedBy(space.s12)) { content() }
        }
    }
}

@Composable
private fun DialogButtons(
    confirmLabel: String?,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s4, Alignment.End)) {
        FolioButton("Cancel", onDismiss, style = FolioButtonStyle.Text, textColor = FolioTheme.colors.textPrimary)
        if (confirmLabel != null) FolioButton(confirmLabel, onConfirm, style = FolioButtonStyle.Text, enabled = confirmEnabled)
    }
}
