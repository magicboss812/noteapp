package dev.folio.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.FolioIconButtonStyle
import dev.folio.core.designsystem.component.SegmentedTabs
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.repo.DocumentSort
import dev.folio.feature.library.state.LibraryBrowseState
import dev.folio.feature.library.state.LibraryDocItem
import dev.folio.feature.library.state.LibraryFolderItem
import dev.folio.feature.library.state.LibraryTab
import dev.folio.feature.library.state.LibraryViewMode
import dev.folio.feature.library.state.breadcrumb
import dev.folio.feature.library.state.childFolders
import dev.folio.feature.library.state.dateSubtitle
import dev.folio.feature.library.state.existingFolder
import dev.folio.feature.library.state.folderSummaries
import dev.folio.feature.library.state.greeting
import dev.folio.feature.library.state.label
import dev.folio.feature.library.state.visibleDocuments
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import java.time.ZoneId

// Below this width the sidebar becomes a drawer (portrait on the Pad 7 is 777 dp wide).
private val DRAWER_BELOW = 840.dp
private val HEADER_TOP = 48.dp
private val HEADER_SIDE = 24.dp
private val FAB_SIZE = 48.dp
private val FAB_SIDE = 16.dp
private val FAB_BOTTOM = 48.dp
private val MENU_WIDTH = 200.dp
private val MENU_ROW = 42.dp
private val SORT_HEIGHT = 44.dp

/** What the "New" menu can do; a null action is a destination that does not exist yet and shows disabled. */
internal data class NewActions(
    val note: () -> Unit,
    val folder: (() -> Unit)? = null,
    val importPdf: (() -> Unit)? = null,
    val importFolio: (() -> Unit)? = null,
)

/**
 * The library home: sidebar (permanent in landscape, a drawer in portrait), greeting header, tabs with sort, view and
 * select controls, the documents, and the New button (11-design-system.md#library-screen). Stateless.
 */
@Composable
internal fun LibraryHome(
    browse: LibraryBrowseState,
    documents: ImmutableList<LibraryDocItem>,
    folders: ImmutableList<LibraryFolderItem>,
    nowMs: Long,
    zone: ZoneId,
    onSelectTab: (LibraryTab) -> Unit,
    onSetSort: (DocumentSort) -> Unit,
    onToggleViewMode: () -> Unit,
    onToggleSelectMode: () -> Unit,
    onToggleSidebar: () -> Unit,
    onOpenDocument: (String) -> Unit,
    newActions: NewActions,
    modifier: Modifier = Modifier,
    notes: NoteActions = NoteActions(),
    folderActions: FolderActions = FolderActions(),
    onSelectAll: (List<String>) -> Unit = {},
    onSearch: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
) {
    // A folder renamed, moved or deleted elsewhere falls back to the root instead of showing an empty view.
    val folder = existingFolder(folders, browse.folder)
    val visible = visibleDocuments(documents, browse.tab, browse.sort, folder)
    val subfolders =
        remember(folders, documents, browse.tab, folder) {
            if (browse.tab == LibraryTab.ALL) folderSummaries(childFolders(folders, folder), documents) else persistentListOf()
        }
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var movingSelection by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    BoxWithConstraints(modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        val drawer = maxWidth < DRAWER_BELOW
        val sidebar: @Composable (Modifier) -> Unit = { sidebarModifier ->
            LibrarySidebar(
                collapsed = browse.sidebarCollapsed && !drawer,
                tab = browse.tab,
                folders = folders,
                onToggleCollapse = if (drawer) ({ drawerOpen = false }) else onToggleSidebar,
                onSelectTab = {
                    drawerOpen = false
                    onSelectTab(it)
                },
                onOpenFolder = {
                    drawerOpen = false
                    folderActions.onOpen(it)
                },
                modifier = sidebarModifier,
                currentFolder = folder,
                onOpenSettings = onOpenSettings,
            )
        }
        Row(Modifier.fillMaxSize()) {
            if (!drawer) sidebar(Modifier)
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                    LibraryHeader(
                        greeting = greeting(browse.userName),
                        subtitle = dateSubtitle(nowMs, zone),
                        onMenu = if (drawer) ({ drawerOpen = true }) else null,
                        onSearch = onSearch,
                    )
                    LibraryControls(browse, folder, onSelectTab, folderActions.onOpen, onSetSort, onToggleViewMode, onToggleSelectMode)
                    LibraryDocuments(
                        documents = visible,
                        folders = folders,
                        subfolders = subfolders,
                        viewMode = browse.viewMode,
                        selectMode = browse.selectMode,
                        selected = browse.selected,
                        nowMs = nowMs,
                        zone = zone,
                        emptyTitle = emptyTitle(browse.tab, folder),
                        emptyHint = emptyHint(browse.tab, folder),
                        onOpenDocument = onOpenDocument,
                        notes = notes,
                        folderActions = folderActions,
                    )
                }
                if (browse.selectMode) {
                    SelectionBar(
                        count = browse.selected.size,
                        allSelected = visible.isNotEmpty() && visible.all { it.path in browse.selected },
                        favoriteAll =
                            visible.filter { it.path in browse.selected }.let { picked ->
                                picked.isNotEmpty() &&
                                    picked.all { it.favorite }
                            },
                        onSelectAll = { onSelectAll(visible.map { it.path }) },
                        onMove = { movingSelection = true },
                        onFavorite = { favorite -> notes.onSetFavorite(browse.selected, favorite) },
                        onDelete = { notes.onDelete(browse.selected) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = FAB_BOTTOM),
                    )
                } else {
                    NewButton(
                        newActions.copy(folder = { creatingFolder = true }),
                        Modifier.align(Alignment.BottomEnd).padding(end = FAB_SIDE, bottom = FAB_BOTTOM),
                    )
                }
            }
        }
        if (movingSelection) {
            MoveNotesDialog(
                folders = folders,
                currentFolder = null,
                onMove = {
                    movingSelection = false
                    notes.onMove(browse.selected, it)
                },
                onDismiss = { movingSelection = false },
            )
        }
        if (creatingFolder) {
            NameDialog(
                heading = "New folder",
                fieldLabel = "Folder name",
                confirmLabel = "Create",
                initial = "",
                onConfirm = {
                    creatingFolder = false
                    folderActions.onCreate(folder, it)
                },
                onDismiss = { creatingFolder = false },
            )
        }
        if (drawer && drawerOpen) {
            Box(Modifier.fillMaxSize().background(FolioTheme.colors.scrim).clickable(onClickLabel = "Close menu") { drawerOpen = false })
            sidebar(Modifier)
        }
    }
}

private fun emptyTitle(
    tab: LibraryTab,
    folder: String,
) = if (tab == LibraryTab.ALL && folder.isNotEmpty()) {
    "This folder is empty"
} else {
    when (tab) {
        LibraryTab.ALL -> "No notes yet"
        LibraryTab.FAVORITES -> "No favorites yet"
        LibraryTab.TAGS -> "No tags yet"
        LibraryTab.BIN -> "The bin is empty"
    }
}

private fun emptyHint(
    tab: LibraryTab,
    folder: String,
) = if (tab == LibraryTab.ALL && folder.isNotEmpty()) {
    "Tap the plus button to add a note or a folder."
} else {
    when (tab) {
        LibraryTab.ALL -> "Tap the plus button to start a note."
        LibraryTab.FAVORITES -> "Mark a note as favorite and it shows up here."
        LibraryTab.TAGS -> "Tags you add to notes show up here."
        LibraryTab.BIN -> "Deleted notes stay here for 30 days."
    }
}

@Composable
private fun LibraryHeader(
    greeting: String,
    subtitle: String,
    onMenu: (() -> Unit)?,
    onSearch: (() -> Unit)?,
) {
    val colors = FolioTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(start = HEADER_SIDE, end = HEADER_SIDE, top = HEADER_TOP),
        horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s8),
        verticalAlignment = Alignment.Top,
    ) {
        if (onMenu != null) FolioIconButton(FolioIcons.Menu, "Open sidebar", onMenu)
        Column(Modifier.weight(1f)) {
            Text(text = greeting, style = FolioTheme.type.display, color = colors.textPrimary, maxLines = 1)
            Text(text = subtitle, style = FolioTheme.type.body, color = colors.textSecondary, maxLines = 1)
        }
        FolioIconButton(
            icon = FolioIcons.Search,
            contentDescription = "Search",
            onClick = { onSearch?.invoke() },
            enabled = onSearch != null,
            style = FolioIconButtonStyle.Outlined,
        )
    }
}

@Composable
private fun LibraryControls(
    browse: LibraryBrowseState,
    folder: String,
    onSelectTab: (LibraryTab) -> Unit,
    onOpenFolder: (String) -> Unit,
    onSetSort: (DocumentSort) -> Unit,
    onToggleViewMode: () -> Unit,
    onToggleSelectMode: () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val tabs = LibraryTab.entries.map { it.label }.toImmutableList()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = HEADER_SIDE, vertical = space.s16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (folder.isNotEmpty() && browse.tab == LibraryTab.ALL) {
            // Inside a folder the chips give way to the breadcrumb (DESIGN folder view).
            FolderBreadcrumb(breadcrumb(folder), onOpenFolder, Modifier.weight(1f))
        } else {
            SegmentedTabs(tabs, browse.tab.ordinal, onSelect = { onSelectTab(LibraryTab.entries[it]) })
            Box(Modifier.weight(1f))
        }
        SortMenu(browse.sort, onSetSort)
        FolioIconButton(
            icon = if (browse.viewMode == LibraryViewMode.GRID) FolioIcons.List else FolioIcons.LayoutGrid,
            contentDescription = if (browse.viewMode == LibraryViewMode.GRID) "Show as list" else "Show as grid",
            onClick = onToggleViewMode,
        )
        Box(
            Modifier
                .size(space.touchTarget)
                .clip(FolioTheme.shapes.full)
                .background(if (browse.selectMode) colors.accentContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            FolioIconButton(
                icon = FolioIcons.CircleCheck,
                contentDescription = if (browse.selectMode) "Leave select mode" else "Select notes",
                onClick = onToggleSelectMode,
                tint = if (browse.selectMode) colors.accent else Color.Unspecified,
            )
        }
    }
}

@Composable
private fun SortMenu(
    sort: DocumentSort,
    onSetSort: (DocumentSort) -> Unit,
) {
    val colors = FolioTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .heightIn(min = SORT_HEIGHT)
                .clip(FolioTheme.shapes.stadium)
                .clickable(role = Role.Button, onClickLabel = "Sort") { open = true }
                .padding(horizontal = FolioTheme.space.s12),
            horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = sort.label, style = FolioTheme.type.labelSmall, color = colors.textPrimary)
            Icon(
                FolioIcons.ArrowDownWideNarrow,
                contentDescription = null,
                modifier = Modifier.size(FolioTheme.space.iconMenu),
                tint = colors.textPrimary,
            )
        }
        FolioMenu(expanded = open, onDismiss = { open = false }) {
            DocumentSort.entries.forEach { option ->
                MenuRow(
                    label = option.label,
                    selected = option == sort,
                    onClick = {
                        open = false
                        onSetSort(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun NewButton(
    actions: NewActions,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    var open by rememberSaveable { mutableStateOf(false) }
    Box(modifier) {
        Box(
            Modifier
                .size(FAB_SIZE)
                .clip(FolioTheme.shapes.full)
                .background(colors.accentFill)
                .clickable(role = Role.Button, onClickLabel = "New") { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                FolioIcons.Plus,
                contentDescription = "New",
                modifier = Modifier.size(FolioTheme.space.iconToolbar),
                tint = colors.onAccent,
            )
        }
        FolioMenu(expanded = open, onDismiss = { open = false }) {
            NewMenuRow(FolioIcons.FileText, "Note", actions.note) { open = false }
            NewMenuRow(FolioIcons.FolderPlus, "Folder", actions.folder) { open = false }
            NewMenuRow(FolioIcons.FileUp, "Import PDF", actions.importPdf) { open = false }
            NewMenuRow(FolioIcons.Download, "Import .folio", actions.importFolio) { open = false }
        }
    }
}

/** Batch actions on the ticked notes, floating where the New button sits otherwise. */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    favoriteAll: Boolean,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onFavorite: (Boolean) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Row(
        modifier
            .background(colors.surface, FolioTheme.shapes.stadium)
            .border(space.borderWidth, colors.border, FolioTheme.shapes.stadium)
            .padding(horizontal = space.s16),
        horizontalArrangement = Arrangement.spacedBy(space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 0) "Select notes" else "$count selected",
            style = FolioTheme.type.labelSmall,
            color = colors.textPrimary,
            modifier = Modifier.padding(end = space.s8),
        )
        FolioIconButton(
            FolioIcons.ListChecks,
            if (allSelected) "All selected" else "Select all",
            onSelectAll,
            enabled = !allSelected,
        )
        FolioIconButton(FolioIcons.Folder, "Move selected", onMove, enabled = count > 0)
        FolioIconButton(
            FolioIcons.Star,
            if (favoriteAll) "Remove favorite from selected" else "Favorite selected",
            { onFavorite(!favoriteAll) },
            enabled = count > 0,
            tint = if (favoriteAll) colors.warning else Color.Unspecified,
        )
        FolioIconButton(FolioIcons.Trash, "Delete selected", onDelete, enabled = count > 0)
    }
}

@Composable
private fun NewMenuRow(
    icon: ImageVector,
    label: String,
    action: (() -> Unit)?,
    close: () -> Unit,
) {
    MenuRow(
        label = label,
        icon = icon,
        enabled = action != null,
        onClick = {
            close()
            action?.invoke()
        },
    )
}

@Composable
internal fun FolioMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = FolioTheme.colors
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.width(MENU_WIDTH),
        shape = FolioTheme.shapes.menu,
        containerColor = colors.surface,
        tonalElevation = 0.dp,
        shadowElevation = FolioTheme.elevation.popover.blur,
    ) { content() }
}

@Composable
internal fun MenuRow(
    label: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = FolioTheme.colors
    val tint =
        when {
            !enabled -> colors.textDisabled
            destructive -> colors.danger
            selected -> colors.accent
            else -> colors.textPrimary
        }
    DropdownMenuItem(
        text = { Text(text = label, style = FolioTheme.type.body, color = tint) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = MENU_ROW),
        leadingIcon =
            icon?.let {
                {
                    Icon(
                        it,
                        contentDescription = null,
                        modifier = Modifier.size(FolioTheme.space.iconMenu),
                        tint = tint,
                    )
                }
            },
        trailingIcon =
            if (selected) {
                (
                    {
                        Icon(FolioIcons.Check, contentDescription = null, modifier = Modifier.size(FolioTheme.space.iconMenu), tint = tint)
                    }
                )
            } else {
                null
            },
        enabled = enabled,
    )
}
