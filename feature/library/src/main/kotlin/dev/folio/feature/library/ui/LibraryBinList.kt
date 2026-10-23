package dev.folio.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.repo.BinRetention
import dev.folio.feature.library.state.LibraryBinItem
import dev.folio.feature.library.state.LibraryBinState
import dev.folio.feature.library.state.daysLeftText
import dev.folio.feature.library.state.deletedText
import dev.folio.feature.library.state.label

private val BIN_MAX_WIDTH = 760.dp
private val BIN_ROW_HEIGHT = 72.dp
private val BIN_ICON_TILE = 44.dp
private val BIN_SIDE = 24.dp

// An entry this close to its purge date is shown in the warning color.
private const val URGENT_DAYS = 3

/** What the Bin tab reports; the defaults are no-ops so previews and tests pass only what they check. */
data class BinActions(
    val onRestore: (LibraryBinItem) -> Unit = {},
    val onDeleteForever: (LibraryBinItem) -> Unit = {},
    val onEmpty: () -> Unit = {},
    val onSetRetention: (BinRetention) -> Unit = {},
)

private sealed interface BinDialog {
    data class DeleteForever(
        val item: LibraryBinItem,
    ) : BinDialog

    data object Empty : BinDialog
}

/**
 * The Bin tab: a retention picker and Empty bin above one row per deleted item with its age, the days left, Restore and
 * Delete forever. Both destructive actions ask first (09-storage-library.md#trash).
 */
@Composable
internal fun LibraryBinList(
    bin: LibraryBinState,
    actions: BinActions,
    modifier: Modifier = Modifier,
) {
    var dialog by remember { mutableStateOf<BinDialog?>(null) }
    if (bin.items.isEmpty()) {
        Column(modifier.fillMaxSize()) {
            RetentionRow(bin.retention, empty = true, actions)
            EmptyState("The bin is empty", binHint(bin.retention), Modifier.weight(1f))
        }
        return
    }
    Column(modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max = BIN_MAX_WIDTH).fillMaxWidth()) {
            RetentionRow(bin.retention, empty = false, actions, onEmpty = { dialog = BinDialog.Empty })
        }
        LazyColumn(
            Modifier.widthIn(max = BIN_MAX_WIDTH).fillMaxWidth(),
            contentPadding = PaddingValues(start = BIN_SIDE, end = BIN_SIDE, bottom = FAB_CLEARANCE),
        ) {
            items(bin.items, key = { it.trashPath }) { item ->
                BinRow(item, onRestore = { actions.onRestore(item) }, onDelete = { dialog = BinDialog.DeleteForever(item) })
            }
        }
    }
    when (val d = dialog) {
        is BinDialog.DeleteForever -> {
            ConfirmDialog(
                title = "Delete forever?",
                text = "“${d.item.name}” will be removed for good. This cannot be undone.",
                confirmLabel = "Delete forever",
                onConfirm = {
                    dialog = null
                    actions.onDeleteForever(d.item)
                },
                onDismiss = { dialog = null },
            )
        }

        BinDialog.Empty -> {
            ConfirmDialog(
                title = "Empty the bin?",
                text = "Everything in the bin (${bin.items.size}) will be removed for good. This cannot be undone.",
                confirmLabel = "Empty bin",
                onConfirm = {
                    dialog = null
                    actions.onEmpty()
                },
                onDismiss = { dialog = null },
            )
        }

        null -> {
            Unit
        }
    }
}

private fun binHint(retention: BinRetention) =
    retention.days?.let { "Deleted notes stay here for $it days." } ?: "Deleted notes stay here until you delete them for good."

@Composable
private fun RetentionRow(
    retention: BinRetention,
    empty: Boolean,
    actions: BinActions,
    onEmpty: () -> Unit = {},
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = BIN_SIDE - space.s12, vertical = space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            Row(
                Modifier
                    .heightIn(min = space.touchTarget)
                    .clip(FolioTheme.shapes.stadium)
                    .clickable(role = Role.Button, onClickLabel = "Change how long deleted items are kept") { open = true }
                    .padding(horizontal = space.s12),
                horizontalArrangement = Arrangement.spacedBy(space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "Keep deleted items: ${retention.label}", style = FolioTheme.type.labelSmall, color = colors.textSecondary)
                Icon(
                    FolioIcons.ChevronDown,
                    contentDescription = null,
                    modifier = Modifier.size(space.iconMenu),
                    tint = colors.textSecondary,
                )
            }
            FolioMenu(expanded = open, onDismiss = { open = false }) {
                BinRetention.entries.forEach { option ->
                    MenuRow(
                        label = option.label,
                        selected = option == retention,
                        onClick = {
                            open = false
                            actions.onSetRetention(option)
                        },
                    )
                }
            }
        }
        if (!empty) {
            FolioButton("Empty bin", onEmpty, style = FolioButtonStyle.Text, destructive = true)
        }
    }
}

@Composable
private fun BinRow(
    item: LibraryBinItem,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Row(
        Modifier.fillMaxWidth().heightIn(min = BIN_ROW_HEIGHT),
        horizontalArrangement = Arrangement.spacedBy(space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(BIN_ICON_TILE).clip(FolioTheme.shapes.tile).background(colors.surface),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (item.isFolder) FolioIcons.Folder else FolioIcons.FileText,
                contentDescription = null,
                modifier = Modifier.size(space.iconMenu),
                tint = colors.textSecondary,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space.s2)) {
            Text(
                text = item.name,
                style = FolioTheme.type.bodyMedium,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = deletedText(item.daysAgo), style = FolioTheme.type.caption, color = colors.textTertiary, maxLines = 1)
        }
        item.daysLeft?.let { DaysLeftBadge(it) }
        FolioIconButton(FolioIcons.RotateCcw, contentDescription = "Restore ${item.name}", onClick = onRestore)
        FolioIconButton(FolioIcons.Trash, contentDescription = "Delete ${item.name} forever", onClick = onDelete, tint = colors.danger)
    }
}

@Composable
private fun DaysLeftBadge(daysLeft: Int) {
    val colors = FolioTheme.colors
    val urgent = daysLeft <= URGENT_DAYS
    Text(
        text = daysLeftText(daysLeft),
        style = FolioTheme.type.caption,
        color = if (urgent) colors.danger else colors.textSecondary,
        modifier =
            Modifier
                .background(colors.surface, FolioTheme.shapes.stadium)
                .padding(horizontal = FolioTheme.space.s12, vertical = FolioTheme.space.s4),
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    DialogFrame(title, onDismiss) {
        Text(text = text, style = FolioTheme.type.body, color = FolioTheme.colors.textSecondary)
        DialogButtons(confirmLabel = confirmLabel, confirmEnabled = true, onConfirm = onConfirm, onDismiss = onDismiss)
    }
}
