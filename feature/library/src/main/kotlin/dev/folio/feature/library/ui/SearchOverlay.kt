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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioColors
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.repo.HighlightedText
import dev.folio.feature.library.state.LibraryFolderItem
import dev.folio.feature.library.state.SearchResultItem
import dev.folio.feature.library.state.SearchUiState
import kotlinx.collections.immutable.ImmutableList

private val PANEL_WIDTH = 640.dp
private val PANEL_TOP = 72.dp
private val PANEL_MAX_HEIGHT = 560.dp
private val FILTER_HEIGHT = 36.dp
private val INDENT = 12.dp
private val MENU_MAX_HEIGHT = 320.dp
private const val ROOT_LABEL = "Whole library"

/** What the search overlay reports; every default is a no-op so previews and tests pass only what they check. */
data class SearchActions(
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onFolder: (String?) -> Unit = {},
    val onTag: (String?) -> Unit = {},
    val onUseRecent: (String) -> Unit = {},
    val onClearRecent: () -> Unit = {},
    /** The search key was pressed or a result opened: remember the query. */
    val onCommit: () -> Unit = {},
    /** A result was opened: the note's library path and the query, so the editor can show the first match. */
    val onOpenResult: (path: String, query: String) -> Unit = { _, _ -> },
)

/**
 * The search overlay (09-storage-library.md#search): a query field, folder and tag filters, then recent searches
 * while the query is empty, else instant results with highlighted matches. Stateless; opening a result is
 * [SearchActions.onOpenResult].
 */
@Composable
internal fun SearchOverlay(
    state: SearchUiState,
    folders: ImmutableList<LibraryFolderItem>,
    actions: SearchActions,
    modifier: Modifier = Modifier,
) {
    if (!state.open) return
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Dialog(onDismissRequest = actions.onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            modifier.fillMaxWidth().padding(top = PANEL_TOP).padding(horizontal = space.s16),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = PANEL_WIDTH)
                    .fillMaxWidth()
                    .heightIn(max = PANEL_MAX_HEIGHT)
                    .background(colors.surfaceDialog, FolioTheme.shapes.panel)
                    .padding(space.s16),
                verticalArrangement = Arrangement.spacedBy(space.s12),
            ) {
                QueryField(state.query, actions.onQuery, actions.onCommit, actions.onClose)
                FilterRow(state, folders, actions)
                if (state.searching) {
                    Results(state.results, state.query, actions.onCommit) {
                        actions.onClose()
                        actions.onOpenResult(it, state.query.trim())
                    }
                } else {
                    Recent(state.recent, actions)
                }
            }
        }
    }
}

@Composable
private fun QueryField(
    query: String,
    onQuery: (String) -> Unit,
    onCommit: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceInset, FolioTheme.shapes.stadium)
            .border(BorderStroke(space.borderWidth, colors.border), FolioTheme.shapes.stadium)
            .padding(start = space.s16, end = space.s4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.s8),
    ) {
        Icon(FolioIcons.Search, contentDescription = null, modifier = Modifier.size(space.s20), tint = colors.textTertiary)
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = FolioTheme.type.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onCommit() }),
            modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = "Search notes" },
            decorationBox = { inner ->
                Box(Modifier.heightIn(min = space.touchTarget), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text("Search titles and text", style = FolioTheme.type.body, color = colors.textTertiary)
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) FolioIconButton(FolioIcons.X, "Clear search", { onQuery("") })
        FolioButton("Close", onClose, style = FolioButtonStyle.Text, textColor = colors.textPrimary)
    }
}

@Composable
private fun FilterRow(
    state: SearchUiState,
    folders: ImmutableList<LibraryFolderItem>,
    actions: SearchActions,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s8), verticalAlignment = Alignment.CenterVertically) {
        FilterChip(
            icon = FolioIcons.Folder,
            label = state.folder?.let { folders.firstOrNull { f -> f.path == it }?.name ?: it } ?: ROOT_LABEL,
            active = state.folder != null,
            description = "Search in folder",
        ) { dismiss ->
            FilterItem(ROOT_LABEL, 0) {
                actions.onFolder(null)
                dismiss()
            }
            folders.forEach { folder ->
                FilterItem(folder.name, folder.depth + 1) {
                    actions.onFolder(folder.path)
                    dismiss()
                }
            }
        }
        FilterChip(
            icon = FolioIcons.Tag,
            label = state.tag?.let { "#$it" } ?: "Any tag",
            active = state.tag != null,
            description = "Search by tag",
        ) { dismiss ->
            FilterItem("Any tag", 0) {
                actions.onTag(null)
                dismiss()
            }
            state.tags.forEach { tag ->
                FilterItem("#$tag", 0) {
                    actions.onTag(tag)
                    dismiss()
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    description: String,
    items: @Composable (dismiss: () -> Unit) -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    var expanded by remember { mutableStateOf(false) }
    Box {
        // The 44 dp box takes the touch; the 36 dp pill inside is only the look.
        Box(
            Modifier
                .heightIn(min = space.touchTarget)
                .clickable(role = Role.Button, onClickLabel = description) { expanded = true },
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .heightIn(min = FILTER_HEIGHT)
                    .clip(FolioTheme.shapes.stadium)
                    .background(if (active) colors.accentContainer else colors.surface)
                    .border(space.borderWidth, if (active) colors.accent else colors.border, FolioTheme.shapes.stadium)
                    .padding(horizontal = space.s12),
                horizontalArrangement = Arrangement.spacedBy(space.s4),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val tint = if (active) colors.accent else colors.textSecondary
                Icon(icon, contentDescription = null, modifier = Modifier.size(space.s16), tint = tint)
                Text(label, style = FolioTheme.type.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(FolioIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(space.s16), tint = tint)
            }
        }
        DropdownMenu(expanded, { expanded = false }, Modifier.heightIn(max = MENU_MAX_HEIGHT)) { items { expanded = false } }
    }
}

@Composable
private fun FilterItem(
    label: String,
    depth: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label, style = FolioTheme.type.body, color = FolioTheme.colors.textPrimary, maxLines = 1) },
        onClick = onClick,
        modifier = Modifier.padding(start = INDENT * depth),
    )
}

@Composable
private fun Recent(
    recent: ImmutableList<String>,
    actions: SearchActions,
) {
    if (recent.isEmpty()) {
        Hint("Type to search titles and typed text. Handwriting is not searchable.")
    } else {
        RecentList(recent, actions)
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = FolioTheme.type.body,
        color = FolioTheme.colors.textSecondary,
        modifier = Modifier.padding(vertical = FolioTheme.space.s8),
    )
}

@Composable
private fun RecentList(
    recent: ImmutableList<String>,
    actions: SearchActions,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recent searches", style = FolioTheme.type.label, color = colors.textSecondary, modifier = Modifier.weight(1f))
            FolioButton("Clear", actions.onClearRecent, style = FolioButtonStyle.Text)
        }
        LazyColumn {
            items(recent, key = { it }) { query ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = space.touchTarget)
                        .clickable(role = Role.Button) { actions.onUseRecent(query) }
                        .padding(horizontal = space.s8),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(space.s12),
                ) {
                    Icon(FolioIcons.RotateCcw, contentDescription = null, modifier = Modifier.size(space.s16), tint = colors.textTertiary)
                    Text(query, style = FolioTheme.type.body, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun Results(
    results: ImmutableList<SearchResultItem>,
    query: String,
    onCommit: () -> Unit,
    onOpenDocument: (String) -> Unit,
) {
    if (results.isEmpty()) {
        Hint("No notes match \"${query.trim()}\".")
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s4)) {
            items(results, key = { it.path }) { item ->
                ResultRow(item) {
                    onCommit()
                    onOpenDocument(item.path)
                }
            }
        }
    }
}

@Composable
private fun ResultRow(
    item: SearchResultItem,
    onClick: () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = space.touchTarget)
            .clip(FolioTheme.shapes.menu)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = space.s8, vertical = space.s8),
        verticalArrangement = Arrangement.spacedBy(space.s2),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(space.s8), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = highlighted(item.title, colors),
                style = FolioTheme.type.cardTitle,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = item.folder.ifEmpty { "Library" }.replace("/", " / "),
                style = FolioTheme.type.caption,
                color = colors.textTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (item.snippet.text.isNotEmpty()) {
            Text(
                text = highlighted(item.snippet, colors),
                style = FolioTheme.type.body,
                color = colors.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** [text] with its matched ranges on the search highlight background and in bold. */
private fun highlighted(
    text: HighlightedText,
    colors: FolioColors,
): AnnotatedString =
    buildAnnotatedString {
        append(text.text)
        val style = SpanStyle(color = colors.onSearchHighlight, background = colors.searchHighlight, fontWeight = FontWeight.SemiBold)
        text.ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
