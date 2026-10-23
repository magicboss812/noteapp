package dev.folio.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.CardSize
import dev.folio.core.designsystem.component.FolderCard
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.NoteCard
import dev.folio.core.designsystem.component.NoteCardCover
import dev.folio.core.designsystem.component.NoteCardPaper
import dev.folio.core.designsystem.component.NoteCardTextPreview
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.library.state.CardPreview
import dev.folio.feature.library.state.FolderSummary
import dev.folio.feature.library.state.LibraryDocItem
import dev.folio.feature.library.state.LibraryFolderItem
import dev.folio.feature.library.state.LibraryViewMode
import dev.folio.feature.library.state.cardPreview
import dev.folio.feature.library.state.cardTimestamp
import dev.folio.feature.library.state.folderColor
import dev.folio.feature.library.state.tintName
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.time.ZoneId

private val GUTTER = 24.dp
private val LIST_ROW_HEIGHT = 100.dp
private val LIST_THUMB = 100.dp
private val LIST_THUMB_RADIUS = 10.dp
private const val LIST_COLUMNS = 2

// The meta line of a 195 dp card has room for the timestamp, a star and one tag; list rows and the editor show them all.
private const val CARD_TAGS = 1
private val EMPTY_MAX_TEXT = 360.dp

/** What the note cards report; the defaults are no-ops so previews and tests pass only what they check. */
data class NoteActions(
    val onToggleSelected: (String) -> Unit = {},
    val onRename: (path: String, title: String) -> Unit = { _, _ -> },
    val onMove: (paths: Set<String>, folder: String) -> Unit = { _, _ -> },
    val onDuplicate: (String) -> Unit = {},
    val onSetFavorite: (paths: Set<String>, favorite: Boolean) -> Unit = { _, _ -> },
    val onSetTags: (path: String, tags: List<String>) -> Unit = { _, _ -> },
    val onDelete: (paths: Set<String>) -> Unit = {},
    /** Decoded cover thumbnail of a note; null while missing. */
    val loadCover: suspend (LibraryDocItem) -> ImageBitmap? = { null },
)

/** What the folder cards report; the defaults are no-ops so previews and tests pass only what they check. */
data class FolderActions(
    val onOpen: (String) -> Unit = {},
    val onCreate: (parent: String, name: String) -> Unit = { _, _ -> },
    val onRename: (path: String, name: String) -> Unit = { _, _ -> },
    val onSetTint: (path: String, tint: String?) -> Unit = { _, _ -> },
    val onMove: (path: String, parent: String) -> Unit = { _, _ -> },
    val onDelete: (path: String) -> Unit = {},
)

/**
 * The folders and documents of the current tab: 195 dp cards in a grid with a 24 dp gutter, or two columns of 100 dp
 * rows (11-design-system.md#library-screen). Folders come first. In select mode a tap ticks a note; a long press
 * starts it.
 */
@Composable
internal fun LibraryDocuments(
    documents: ImmutableList<LibraryDocItem>,
    folders: ImmutableList<LibraryFolderItem>,
    subfolders: ImmutableList<FolderSummary>,
    viewMode: LibraryViewMode,
    selectMode: Boolean,
    selected: ImmutableSet<String>,
    nowMs: Long,
    zone: ZoneId,
    emptyTitle: String,
    emptyHint: String,
    onOpenDocument: (String) -> Unit,
    notes: NoteActions,
    folderActions: FolderActions,
    modifier: Modifier = Modifier,
    knownTags: ImmutableList<String> = persistentListOf(),
) {
    if (documents.isEmpty() && subfolders.isEmpty()) {
        EmptyState(emptyTitle, emptyHint, modifier)
        return
    }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<LibraryDocItem?>(null) }
    var moving by remember { mutableStateOf<LibraryDocItem?>(null) }
    var tagging by remember { mutableStateOf<LibraryDocItem?>(null) }
    var folderMenuFor by remember { mutableStateOf<String?>(null) }
    var folderDialog by remember { mutableStateOf<FolderDialog?>(null) }
    val folderMenu: @Composable (FolderSummary) -> Unit = { summary ->
        FolderMenu(
            expanded = folderMenuFor == summary.folder.path,
            onDismiss = { folderMenuFor = null },
            onAction = { action ->
                folderMenuFor = null
                val dialog = action.dialogFor(summary.folder)
                if (dialog == null) folderActions.onDelete(summary.folder.path) else folderDialog = dialog
            },
        )
    }
    val inset = PaddingValues(start = FolioTheme.space.s16, end = FolioTheme.space.s16, top = FolioTheme.space.s8, bottom = FAB_CLEARANCE)
    val spacing = Arrangement.spacedBy(GUTTER)
    val open: (LibraryDocItem) -> Unit = { doc -> if (selectMode) notes.onToggleSelected(doc.path) else onOpenDocument(doc.path) }
    val menu: @Composable (LibraryDocItem) -> Unit = { doc ->
        NoteMenu(
            expanded = menuFor == doc.path,
            favorite = doc.favorite,
            onDismiss = { menuFor = null },
            onAction = { action ->
                menuFor = null
                performNoteAction(action, doc, notes, onRename = { renaming = doc }, onMove = { moving = doc }, onTags = { tagging = doc })
            },
        )
    }
    when (viewMode) {
        LibraryViewMode.GRID -> {
            LazyVerticalGrid(
                columns = GridCells.FixedSize(CardSize),
                modifier = modifier.fillMaxSize(),
                contentPadding = inset,
                verticalArrangement = spacing,
                horizontalArrangement = spacing,
            ) {
                items(subfolders, key = { "folder:${it.folder.path}" }) { summary ->
                    Box {
                        FolderCard(
                            name = summary.folder.name,
                            noteCount = summary.noteCount,
                            color = folderColor(summary.folder.tint),
                            onClick = { folderActions.onOpen(summary.folder.path) },
                            latestTitle = summary.latest?.title,
                            latestPreview = summary.latest?.textPreview?.takeIf { it.isNotBlank() },
                            onLongClick = { folderMenuFor = summary.folder.path },
                            onOverflow = { folderMenuFor = summary.folder.path },
                        )
                        folderMenu(summary)
                    }
                }
                items(documents, key = { it.path }) { doc ->
                    val cover = rememberCover(doc, notes.loadCover)
                    Box {
                        NoteCard(
                            title = doc.title,
                            timestamp = cardTimestamp(doc.modifiedMs, nowMs, zone),
                            onClick = { open(doc) },
                            favorite = doc.favorite,
                            tags = doc.tags.take(CARD_TAGS).toImmutableList(),
                            selected = doc.path in selected,
                            selectMode = selectMode,
                            onLongClick = { if (!selectMode) notes.onToggleSelected(doc.path) },
                            onOverflow = if (selectMode) null else ({ menuFor = doc.path }),
                        ) { NotePreview(doc, cover) }
                        menu(doc)
                    }
                }
            }
        }

        LibraryViewMode.LIST -> {
            LazyVerticalGrid(
                columns = GridCells.Fixed(LIST_COLUMNS),
                modifier = modifier.fillMaxSize(),
                contentPadding = inset,
                verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s16),
                horizontalArrangement = spacing,
            ) {
                items(subfolders, key = { "folder:${it.folder.path}" }) { summary ->
                    Box {
                        FolderListRow(
                            summary = summary,
                            onClick = { folderActions.onOpen(summary.folder.path) },
                            onLongClick = { folderMenuFor = summary.folder.path },
                            onOverflow = { folderMenuFor = summary.folder.path },
                        )
                        folderMenu(summary)
                    }
                }
                items(documents, key = { it.path }) { doc ->
                    Box {
                        DocumentListRow(
                            doc = doc,
                            cover = rememberCover(doc, notes.loadCover),
                            selectMode = selectMode,
                            selected = doc.path in selected,
                            onClick = { open(doc) },
                            onLongClick = { if (!selectMode) notes.onToggleSelected(doc.path) },
                            onOverflow = { menuFor = doc.path },
                        )
                        menu(doc)
                    }
                }
            }
        }
    }
    NoteDialogHost(renaming, moving, folders, notes, onCloseRename = { renaming = null }, onCloseMove = { moving = null })
    tagging?.let { doc ->
        TagEditorDialog(
            noteTitle = doc.title,
            initial = doc.tags,
            known = knownTags,
            onSave = {
                tagging = null
                notes.onSetTags(doc.path, it)
            },
            onDismiss = { tagging = null },
        )
    }
    FolderDialogHost(folderDialog, folders, folderActions, onClose = { folderDialog = null })
}

/** Runs a note menu entry; rename, move and tags only open a dialog, so they are handed back through [onRename], [onMove] and [onTags]. */
private fun performNoteAction(
    action: NoteAction,
    doc: LibraryDocItem,
    notes: NoteActions,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onTags: () -> Unit,
) {
    when (action) {
        NoteAction.Rename -> onRename()
        NoteAction.Move -> onMove()
        NoteAction.Tags -> onTags()
        NoteAction.Duplicate -> notes.onDuplicate(doc.path)
        NoteAction.ToggleFavorite -> notes.onSetFavorite(setOf(doc.path), !doc.favorite)
        NoteAction.Delete -> notes.onDelete(setOf(doc.path))
    }
}

/** The dialog a folder menu entry opens; null for Delete, which acts at once. */
private fun FolderAction.dialogFor(folder: LibraryFolderItem): FolderDialog? =
    when (this) {
        FolderAction.Rename -> FolderDialog.Rename(folder)
        FolderAction.Color -> FolderDialog.Color(folder)
        FolderAction.Move -> FolderDialog.Move(folder)
        FolderAction.Delete -> null
    }

/** The folder dialog that is open; the folder is kept as it was when the menu entry was picked. */
private sealed interface FolderDialog {
    val folder: LibraryFolderItem

    data class Rename(
        override val folder: LibraryFolderItem,
    ) : FolderDialog

    data class Color(
        override val folder: LibraryFolderItem,
    ) : FolderDialog

    data class Move(
        override val folder: LibraryFolderItem,
    ) : FolderDialog
}

@Composable
private fun FolderDialogHost(
    dialog: FolderDialog?,
    folders: ImmutableList<LibraryFolderItem>,
    actions: FolderActions,
    onClose: () -> Unit,
) {
    when (dialog) {
        null -> {
            Unit
        }

        is FolderDialog.Rename -> {
            NameDialog(
                heading = "Rename folder",
                fieldLabel = "Folder name",
                confirmLabel = "Rename",
                initial = dialog.folder.name,
                onConfirm = {
                    onClose()
                    actions.onRename(dialog.folder.path, it)
                },
                onDismiss = onClose,
            )
        }

        is FolderDialog.Color -> {
            FolderColorDialog(
                current = folderColor(dialog.folder.tint),
                onPick = {
                    onClose()
                    actions.onSetTint(dialog.folder.path, tintName(it))
                },
                onDismiss = onClose,
            )
        }

        is FolderDialog.Move -> {
            MoveNotesDialog(
                folders = folders,
                currentFolder = dialog.folder.parent,
                movingFolder = dialog.folder.path,
                onMove = {
                    onClose()
                    actions.onMove(dialog.folder.path, it)
                },
                onDismiss = onClose,
            )
        }
    }
}

/** The rename and move dialogs of the note menu; [onCloseRename] and [onCloseMove] clear the state that opened them. */
@Composable
private fun NoteDialogHost(
    renaming: LibraryDocItem?,
    moving: LibraryDocItem?,
    folders: ImmutableList<LibraryFolderItem>,
    notes: NoteActions,
    onCloseRename: () -> Unit,
    onCloseMove: () -> Unit,
) {
    if (renaming != null) {
        RenameNoteDialog(
            title = renaming.title,
            onRename = {
                onCloseRename()
                notes.onRename(renaming.path, it)
            },
            onDismiss = onCloseRename,
        )
    }
    if (moving != null) {
        MoveNotesDialog(
            folders = folders,
            currentFolder = moving.folder,
            onMove = {
                onCloseMove()
                notes.onMove(setOf(moving.path), it)
            },
            onDismiss = onCloseMove,
        )
    }
}

@Composable
private fun rememberCover(
    doc: LibraryDocItem,
    load: suspend (LibraryDocItem) -> ImageBitmap?,
): ImageBitmap? {
    val currentLoad by rememberUpdatedState(load)
    if (!doc.hasCover) return null
    return produceState<ImageBitmap?>(null, doc.path, doc.modifiedMs) { value = currentLoad(doc) }.value
}

/** Cover thumbnail, else the text excerpt, else a blank paper (see [cardPreview]). */
@Composable
internal fun ColumnScope.NotePreview(
    doc: LibraryDocItem,
    cover: ImageBitmap?,
) {
    when (val preview = cardPreview(doc)) {
        CardPreview.Cover -> NoteCardCover(cover)
        is CardPreview.Excerpt -> NoteCardTextPreview(preview.text)
        CardPreview.Blank -> NoteCardPaper()
    }
}

@Composable
private fun DocumentListRow(
    doc: LibraryDocItem,
    cover: ImageBitmap?,
    selectMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onOverflow: () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val thumbShape = RoundedCornerShape(LIST_THUMB_RADIUS)
    Row(
        Modifier
            .fillMaxWidth()
            .height(LIST_ROW_HEIGHT)
            .clip(FolioTheme.shapes.tile)
            .combinedClickable(onClickLabel = if (selectMode) "Select" else "Open", onLongClick = onLongClick, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            Modifier
                .size(LIST_THUMB)
                .clip(thumbShape)
                .background(colors.surface)
                .border(
                    if (selected) space.selectedBorderWidth else space.borderWidth,
                    if (selected) colors.accent else colors.border,
                    thumbShape,
                ),
        ) { NotePreview(doc, cover) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space.s2)) {
            Text(
                text = listMeta(doc),
                style = FolioTheme.type.caption,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = doc.title,
                style = FolioTheme.type.bodyMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selectMode) {
            Icon(
                if (selected) FolioIcons.CircleCheck else FolioIcons.Circle,
                contentDescription = if (selected) "Selected" else "Not selected",
                modifier = Modifier.size(space.iconMenu),
                tint = if (selected) colors.accent else colors.textTertiary,
            )
        } else {
            FolioIconButton(FolioIcons.Ellipsis, contentDescription = "More", onClick = onOverflow, tint = colors.textTertiary)
        }
    }
}

private fun listMeta(doc: LibraryDocItem): String {
    val pages = if (doc.pageCount == 1) "1 page" else "${doc.pageCount} pages"
    return (listOf(pages) + doc.tags.map { "#$it" }).joinToString("  ")
}

@Composable
private fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    Column(
        modifier.fillMaxSize().padding(bottom = FAB_CLEARANCE),
        verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s8, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = title, style = FolioTheme.type.title, color = colors.textPrimary, textAlign = TextAlign.Center)
        Text(
            text = hint,
            style = FolioTheme.type.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = EMPTY_MAX_TEXT).padding(horizontal = FolioTheme.space.s24),
        )
    }
}

/** Space below the last row so the floating New button never covers a card. */
internal val FAB_CLEARANCE = 112.dp
