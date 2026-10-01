package dev.folio.feature.library.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.feature.library.state.LibraryDocItem
import dev.folio.feature.library.state.LibraryEntryViewModel
import dev.folio.feature.library.state.NewNoteState
import dev.folio.feature.library.state.NewNoteViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

// Placeholder layout until the library screens (P05): one centered column on the library background.
private val CONTENT_MAX_WIDTH = 560.dp
private val ROW_MIN_HEIGHT = 56.dp

// The library gradient reaches its last stop this far down (DESIGN.md bg.library, light top band).
private val LIBRARY_BAND = 220.dp
private const val HEADER_KEY = "header"

// The new-note sheet is wider where the template gallery fits (tablet landscape).
private val NEW_NOTE_WIDE = 640.dp
private val NEW_NOTE_NARROW = 520.dp
private const val WIDE_SHEET_MIN_DP = 900

/**
 * Library entry: onboarding while all-files access is missing, else the library (placeholder until P05);
 * [onOpenDocument] opens a document by library path.
 */
@Composable
fun LibraryEntryRoute(
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryEntryViewModel = hiltViewModel(),
    newNoteViewModel: NewNoteViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val documents by viewModel.documents.collectAsStateWithLifecycle()
    val newNote by newNoteViewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
            documents = documents,
            onOpenDocument = onOpenDocument,
            onNewNote = newNoteViewModel::show,
        )
        (newNote as? NewNoteState.Editing)?.let { editing ->
            NewNoteSheet(
                form = editing.form,
                onChange = newNoteViewModel::update,
                // The placeholder has no folders yet: new notes go to the library root until P05.
                onCreate = { newNoteViewModel.create(folder = "", onCreated = onOpenDocument) },
                onClose = newNoteViewModel::dismiss,
                creating = editing.creating,
                error = editing.error,
                width = if (LocalConfiguration.current.screenWidthDp >= WIDE_SHEET_MIN_DP) NEW_NOTE_WIDE else NEW_NOTE_NARROW,
            )
        }
    }
}

/** Stateless entry screen. */
@Composable
fun LibraryEntryScreen(
    state: LibraryAccessState,
    onGrantAccess: () -> Unit,
    modifier: Modifier = Modifier,
    documents: ImmutableList<LibraryDocItem> = persistentListOf(),
    onOpenDocument: (String) -> Unit = {},
    onNewNote: () -> Unit = {},
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
                LibraryPlaceholder(state.rootPath, documents, onOpenDocument, onNewNote)
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

@Composable
private fun LibraryHeader(
    rootPath: String,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
) {
    Column(modifier, horizontalAlignment = horizontalAlignment) {
        Text(text = "Library", style = FolioTheme.type.display, color = FolioTheme.colors.textPrimary)
        Text(text = rootPath, style = FolioTheme.type.body, color = FolioTheme.colors.textSecondary)
    }
}

@Composable
private fun LibraryPlaceholder(
    rootPath: String,
    documents: ImmutableList<LibraryDocItem>,
    onOpenDocument: (String) -> Unit,
    onNewNote: () -> Unit,
) {
    if (documents.isEmpty()) {
        CenteredColumn {
            LibraryHeader(rootPath, horizontalAlignment = Alignment.CenterHorizontally)
            FolioButton("New note", onClick = onNewNote)
        }
        return
    }
    val space = FolioTheme.space
    LazyColumn(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(space.s24),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item(key = HEADER_KEY) {
            Column(
                Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth().padding(bottom = space.s24),
                verticalArrangement = Arrangement.spacedBy(space.s16),
            ) {
                LibraryHeader(rootPath)
                FolioButton("New note", onClick = onNewNote)
            }
        }
        items(documents, key = { it.path }) { doc -> DocumentRow(doc, onOpenDocument) }
    }
}

@Composable
private fun DocumentRow(
    doc: LibraryDocItem,
    onOpenDocument: (String) -> Unit,
) {
    val colors = FolioTheme.colors
    Column(
        modifier =
            Modifier
                .widthIn(max = CONTENT_MAX_WIDTH)
                .fillMaxWidth()
                .heightIn(min = ROW_MIN_HEIGHT)
                .clickable(onClickLabel = "Open") { onOpenDocument(doc.path) }
                .padding(vertical = FolioTheme.space.s8),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = doc.title, style = FolioTheme.type.cardTitle, color = colors.textPrimary)
        val folder = doc.folder.ifEmpty { "Library" }
        val pages = if (doc.pageCount == 1) "1 page" else "${doc.pageCount} pages"
        Text(text = "$folder · $pages", style = FolioTheme.type.caption, color = colors.textTertiary)
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
