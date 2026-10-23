package dev.folio.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.CardSize
import dev.folio.core.designsystem.component.NoteCard
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.library.state.LibraryDocItem
import dev.folio.feature.library.state.LibraryViewMode
import dev.folio.feature.library.state.cardTimestamp
import kotlinx.collections.immutable.ImmutableList
import java.time.ZoneId

private val GUTTER = 24.dp
private val LIST_ROW_HEIGHT = 100.dp
private val LIST_THUMB = 100.dp
private val LIST_THUMB_RADIUS = 10.dp
private const val LIST_COLUMNS = 2
private val EMPTY_MAX_TEXT = 360.dp

/**
 * The documents of the current tab: 195 dp cards in a grid with a 24 dp gutter, or two columns of 100 dp rows
 * (11-design-system.md#library-screen). Cards show the title and timestamp; previews and menus arrive with P05-T02.
 */
@Composable
internal fun LibraryDocuments(
    documents: ImmutableList<LibraryDocItem>,
    viewMode: LibraryViewMode,
    nowMs: Long,
    zone: ZoneId,
    emptyTitle: String,
    emptyHint: String,
    onOpenDocument: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (documents.isEmpty()) {
        EmptyState(emptyTitle, emptyHint, modifier)
        return
    }
    val inset = PaddingValues(start = FolioTheme.space.s16, end = FolioTheme.space.s16, top = FolioTheme.space.s8, bottom = FAB_CLEARANCE)
    val spacing = Arrangement.spacedBy(GUTTER)
    when (viewMode) {
        LibraryViewMode.GRID -> {
            LazyVerticalGrid(
                columns = GridCells.FixedSize(CardSize),
                modifier = modifier.fillMaxSize(),
                contentPadding = inset,
                verticalArrangement = spacing,
                horizontalArrangement = spacing,
            ) {
                items(documents, key = { it.path }) { doc ->
                    NoteCard(
                        title = doc.title,
                        timestamp = cardTimestamp(doc.modifiedMs, nowMs, zone),
                        onClick = { onOpenDocument(doc.path) },
                        favorite = doc.favorite,
                    )
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
                items(documents, key = { it.path }) { doc -> DocumentListRow(doc, onClick = { onOpenDocument(doc.path) }) }
            }
        }
    }
}

@Composable
private fun DocumentListRow(
    doc: LibraryDocItem,
    onClick: () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val thumbShape = RoundedCornerShape(LIST_THUMB_RADIUS)
    Row(
        Modifier
            .fillMaxWidth()
            .height(LIST_ROW_HEIGHT)
            .clip(FolioTheme.shapes.tile)
            .clickable(onClickLabel = "Open", onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(space.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(LIST_THUMB)
                .clip(thumbShape)
                .background(colors.surface)
                .border(space.borderWidth, colors.border, thumbShape),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(space.s2)) {
            Text(
                text = if (doc.pageCount == 1) "1 page" else "${doc.pageCount} pages",
                style = FolioTheme.type.caption,
                color = colors.textTertiary,
            )
            Text(
                text = doc.title,
                style = FolioTheme.type.bodyMedium,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
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
