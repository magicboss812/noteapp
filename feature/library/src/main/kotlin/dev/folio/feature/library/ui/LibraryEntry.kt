package dev.folio.feature.library.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioSnackbarHost
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.core.storage.repo.DocumentSort
import dev.folio.feature.library.state.LibraryBinState
import dev.folio.feature.library.state.LibraryBinViewModel
import dev.folio.feature.library.state.LibraryBrowseState
import dev.folio.feature.library.state.LibraryBrowseViewModel
import dev.folio.feature.library.state.LibraryDocItem
import dev.folio.feature.library.state.LibraryEntryViewModel
import dev.folio.feature.library.state.LibraryFolderItem
import dev.folio.feature.library.state.LibraryOpsViewModel
import dev.folio.feature.library.state.LibrarySearchViewModel
import dev.folio.feature.library.state.LibraryTab
import dev.folio.feature.library.state.NewNoteState
import dev.folio.feature.library.state.NewNoteViewModel
import dev.folio.feature.library.state.SearchUiState
import dev.folio.feature.library.state.existingFolder
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.time.ZoneId
import java.time.ZoneOffset

private val CONTENT_MAX_WIDTH = 560.dp

// The library gradient reaches its last stop this far down (DESIGN.md bg.library, light top band).
private val LIBRARY_BAND = 220.dp

// The new-note sheet is wider where the template gallery fits (tablet landscape).
private val NEW_NOTE_WIDE = 640.dp
private val NEW_NOTE_NARROW = 520.dp
private const val WIDE_SHEET_MIN_DP = 900

/**
 * Library entry: onboarding while all-files access is missing, else the library home;
 * [onOpenDocument] opens a document by library path.
 */
@Composable
fun LibraryEntryRoute(
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryEntryViewModel = hiltViewModel(),
    browseViewModel: LibraryBrowseViewModel = hiltViewModel(),
    newNoteViewModel: NewNoteViewModel = hiltViewModel(),
    opsViewModel: LibraryOpsViewModel = hiltViewModel(),
    searchViewModel: LibrarySearchViewModel = hiltViewModel(),
    binViewModel: LibraryBinViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val bin by binViewModel.state.collectAsStateWithLifecycle()
    val search by searchViewModel.state.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val browse by browseViewModel.state.collectAsStateWithLifecycle()
    val newNote by newNoteViewModel.state.collectAsStateWithLifecycle()
    // Re-read when the list changes, so the date line and the card timestamps follow the clock.
    val content =
        remember(browse, documents, folders, search, bin) {
            LibraryContent(browse, documents, folders, browseViewModel.nowMs(), ZoneId.systemDefault(), search, bin)
        }
    val actions =
        remember(browseViewModel, opsViewModel, onOpenDocument, newNoteViewModel, searchViewModel, binViewModel) {
            // Batch actions end the selection; a refused one (open document) is reported by the snackbar.
            val batch: (() -> Unit) -> Unit = { run ->
                run()
                browseViewModel.clearSelection()
            }
            LibraryActions(
                onSelectTab = browseViewModel::selectTab,
                onSetSort = browseViewModel::setSort,
                onToggleViewMode = browseViewModel::toggleViewMode,
                onToggleSelectMode = browseViewModel::toggleSelectMode,
                onToggleSidebar = browseViewModel::toggleSidebar,
                onOpenDocument = onOpenDocument,
                onNewNote = newNoteViewModel::show,
                onSelectAll = browseViewModel::selectAll,
                onSelectTag = browseViewModel::selectTag,
                search =
                    SearchActions(
                        onOpen = searchViewModel::open,
                        onClose = searchViewModel::close,
                        onQuery = searchViewModel::setQuery,
                        onFolder = searchViewModel::setFolder,
                        onTag = searchViewModel::setTag,
                        onUseRecent = searchViewModel::useRecent,
                        onClearRecent = searchViewModel::clearRecent,
                        onCommit = searchViewModel::commitQuery,
                    ),
                bin =
                    BinActions(
                        onRestore = binViewModel::restore,
                        onDeleteForever = binViewModel::deleteForever,
                        onEmpty = binViewModel::emptyBin,
                        onSetRetention = binViewModel::setRetention,
                    ),
                folders =
                    FolderActions(
                        onOpen = browseViewModel::openFolder,
                        onCreate = opsViewModel::createFolder,
                        onRename = opsViewModel::renameFolder,
                        onSetTint = opsViewModel::setFolderTint,
                        onMove = opsViewModel::moveFolder,
                        onDelete = opsViewModel::deleteFolder,
                    ),
                notes =
                    NoteActions(
                        onToggleSelected = browseViewModel::toggleSelected,
                        onRename = opsViewModel::rename,
                        onMove = { paths, folder -> batch { opsViewModel.move(paths, folder) } },
                        onDuplicate = opsViewModel::duplicate,
                        onSetFavorite = { paths, favorite -> batch { opsViewModel.setFavorite(paths, favorite) } },
                        onSetTags = opsViewModel::setTags,
                        onDelete = { paths -> batch { opsViewModel.delete(paths) } },
                        loadCover = opsViewModel::cover,
                    ),
            )
        }
    val context = LocalContext.current
    val notice by viewModel.recoveryNotice.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    // One-time recovery report (04-file-format.md#crash-recovery); forgotten once it was on screen.
    LaunchedEffect(notice) {
        notice?.let {
            snackbars.showSnackbar(it.text)
            viewModel.recoveryNoticeShown(it)
        }
    }
    LaunchedEffect(opsViewModel) { opsViewModel.messages.collect { snackbars.showSnackbar(it) } }
    LaunchedEffect(binViewModel) { binViewModel.messages.collect { snackbars.showSnackbar(it) } }
    // The bin list is read when the tab opens (and once the library is usable), not on every index change.
    LaunchedEffect(browse.tab, state is LibraryAccessState.Ready) {
        if (browse.tab == LibraryTab.BIN && state is LibraryAccessState.Ready) binViewModel.refresh()
    }
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    Box(modifier) {
        LibraryEntryScreen(
            state = state,
            onGrantAccess = {
                val uri = Uri.parse("package:${context.packageName}")
                context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
            },
            content = content,
            actions = actions,
        )
        FolioSnackbarHost(
            snackbars,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing).padding(FolioTheme.space.s16),
        )
        (newNote as? NewNoteState.Editing)?.let { editing ->
            NewNoteSheet(
                form = editing.form,
                onChange = newNoteViewModel::update,
                // New notes land in the folder on screen (the root while a stale folder is gone).
                onCreate = { newNoteViewModel.create(folder = existingFolder(folders, browse.folder), onCreated = onOpenDocument) },
                onClose = newNoteViewModel::dismiss,
                creating = editing.creating,
                error = editing.error,
                width = if (LocalConfiguration.current.screenWidthDp >= WIDE_SHEET_MIN_DP) NEW_NOTE_WIDE else NEW_NOTE_NARROW,
            )
        }
    }
}

/** Everything the library home shows besides the access state; the defaults draw an empty library. */
@Immutable
data class LibraryContent(
    val browse: LibraryBrowseState = LibraryBrowseState(),
    val documents: ImmutableList<LibraryDocItem> = persistentListOf(),
    val folders: ImmutableList<LibraryFolderItem> = persistentListOf(),
    val nowMs: Long = 0L,
    val zone: ZoneId = ZoneOffset.UTC,
    val search: SearchUiState = SearchUiState(),
    val bin: LibraryBinState = LibraryBinState(),
)

/** What the library home reports; every default is a no-op so previews and tests pass only what they check. */
@Immutable
data class LibraryActions(
    val onSelectTab: (LibraryTab) -> Unit = {},
    val onSetSort: (DocumentSort) -> Unit = {},
    val onToggleViewMode: () -> Unit = {},
    val onToggleSelectMode: () -> Unit = {},
    val onToggleSidebar: () -> Unit = {},
    val onOpenDocument: (String) -> Unit = {},
    val onNewNote: () -> Unit = {},
    val onSelectAll: (List<String>) -> Unit = {},
    val onSelectTag: (String) -> Unit = {},
    val notes: NoteActions = NoteActions(),
    val folders: FolderActions = FolderActions(),
    val search: SearchActions = SearchActions(),
    val bin: BinActions = BinActions(),
)

/** Stateless entry screen. */
@Composable
fun LibraryEntryScreen(
    state: LibraryAccessState,
    onGrantAccess: () -> Unit,
    modifier: Modifier = Modifier,
    content: LibraryContent = LibraryContent(),
    actions: LibraryActions = LibraryActions(),
) {
    val colors = FolioTheme.colors
    val bandPx = with(LocalDensity.current) { LIBRARY_BAND.toPx() }
    val background = Brush.verticalGradient(listOf(colors.libraryTop, colors.libraryMid, colors.libraryBottom), endY = bandPx)
    Box(modifier = modifier.fillMaxSize().background(colors.libraryBottom).background(background)) {
        when (state) {
            LibraryAccessState.Checking -> {
                Unit
            }

            is LibraryAccessState.NeedsPermission -> {
                StorageOnboarding(state.folder, onGrantAccess)
            }

            is LibraryAccessState.Ready -> {
                LibraryHome(
                    browse = content.browse,
                    documents = content.documents,
                    folders = content.folders,
                    nowMs = content.nowMs,
                    zone = content.zone,
                    onSelectTab = actions.onSelectTab,
                    onSetSort = actions.onSetSort,
                    onToggleViewMode = actions.onToggleViewMode,
                    onToggleSelectMode = actions.onToggleSelectMode,
                    onToggleSidebar = actions.onToggleSidebar,
                    onOpenDocument = actions.onOpenDocument,
                    newActions = NewActions(note = actions.onNewNote),
                    notes = actions.notes,
                    folderActions = actions.folders,
                    onSelectAll = actions.onSelectAll,
                    onSelectTag = actions.onSelectTag,
                    search = content.search,
                    searchActions = actions.search,
                    bin = content.bin,
                    binActions = actions.bin,
                )
            }

            is LibraryAccessState.Failed -> {
                CenteredColumn {
                    Text(
                        text = "The library folder cannot be used: ${state.message}",
                        style = FolioTheme.type.body,
                        color = colors.textPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    val gap = FolioTheme.space.s24
    Column(
        modifier = Modifier.fillMaxSize().padding(gap),
        verticalArrangement = Arrangement.spacedBy(gap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun StorageOnboarding(
    folder: String,
    onGrantAccess: () -> Unit,
) {
    val colors = FolioTheme.colors
    CenteredColumn {
        Text(text = "Keep your notes as files", style = FolioTheme.type.display, color = colors.textPrimary)
        Text(
            text =
                "Folio stores every notebook as a normal file in $folder, so you can copy it to a computer or back it up. " +
                    "Android needs you to allow access to all files once for this; Folio never goes online.",
            style = FolioTheme.type.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH),
        )
        FolioButton("Allow file access", onClick = onGrantAccess)
    }
}

@Preview(name = "onboarding light", widthDp = 1164, heightDp = 777)
@Composable
private fun OnboardingLightPreview() {
    FolioTheme(darkTheme = false) { LibraryEntryScreen(LibraryAccessState.NeedsPermission("Documents/Folio"), onGrantAccess = {}) }
}

@Preview(name = "onboarding dark", widthDp = 1164, heightDp = 777, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingDarkPreview() {
    FolioTheme(darkTheme = true) { LibraryEntryScreen(LibraryAccessState.NeedsPermission("Documents/Folio"), onGrantAccess = {}) }
}

@Preview(name = "library light", widthDp = 1164, heightDp = 777)
@Composable
private fun LibraryLightPreview() {
    FolioTheme(darkTheme = false) {
        LibraryEntryScreen(LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio"), onGrantAccess = {})
    }
}

@Preview(name = "library dark", widthDp = 1164, heightDp = 777, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LibraryDarkPreview() {
    FolioTheme(darkTheme = true) {
        LibraryEntryScreen(LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio"), onGrantAccess = {})
    }
}
