package dev.folio.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.CloseChip
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import kotlinx.collections.immutable.ImmutableList

private val OVERVIEW_CELL_MIN = 180.dp
private val OVERVIEW_BAR_HEIGHT = 56.dp

/**
 * Page grid overview (10-editor-ui.md#pages): every page as a thumbnail. A tap goes to the page; "Select" switches to
 * multi-select, where the bar duplicates or deletes the chosen pages.
 */
@Composable
internal fun PageOverview(
    pages: ImmutableList<PageRef>,
    current: PageId?,
    thumbnail: PageThumbnail,
    actions: PageActions,
    modifier: Modifier = Modifier,
    initiallySelecting: Boolean = false,
    initialSelection: Set<PageId> = emptySet(),
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    var selecting by remember { mutableStateOf(initiallySelecting) }
    var selected by remember { mutableStateOf(initialSelection) }
    val live = pages.map { it.id }.toSet()
    // Pages deleted meanwhile leave the selection.
    val chosen = selected.intersect(live)
    Column(
        modifier
            .fillMaxSize()
            .background(colors.canvas)
            // The overview covers the canvas: swallow taps so none reach it.
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = space.s24, end = space.s8),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = space.s12)) {
                Text("Pages", style = FolioTheme.type.title, color = colors.textPrimary)
                Text(
                    text =
                        if (selecting) {
                            "${chosen.size} selected"
                        } else if (pages.size == 1) {
                            "1 page"
                        } else {
                            "${pages.size} pages"
                        },
                    style = FolioTheme.type.caption,
                    color = colors.textSecondary,
                )
            }
            if (selecting) {
                FolioButton("Duplicate", {
                    actions.onDuplicate(pages.map { it.id }.filter { it in chosen })
                }, style = FolioButtonStyle.Text, enabled = chosen.isNotEmpty())
                FolioButton(
                    "Delete",
                    { actions.onDelete(pages.map { it.id }.filter { it in chosen }) },
                    style = FolioButtonStyle.Text,
                    enabled = chosen.isNotEmpty() && chosen.size < pages.size,
                    destructive = true,
                )
            }
            FolioButton(
                if (selecting) "Done" else "Select",
                {
                    selecting = !selecting
                    selected = emptySet()
                },
                style = FolioButtonStyle.Text,
            )
            CloseChip(actions.onCloseSurface)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(OVERVIEW_CELL_MIN),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = space.s24, end = space.s24, top = space.s8, bottom = OVERVIEW_BAR_HEIGHT),
            horizontalArrangement = Arrangement.spacedBy(space.s16),
            verticalArrangement = Arrangement.spacedBy(space.s16),
        ) {
            itemsIndexed(pages, key = { _, page -> page.id.value }) { index, page ->
                val isCurrent = page.id == current
                val isChosen = page.id in chosen
                val highlighted = (isCurrent && !selecting) || isChosen
                val shape = FolioTheme.shapes.field
                Column(Modifier.animateItem(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .border(
                                if (highlighted) space.selectedBorderWidth else space.borderWidth,
                                if (highlighted) colors.accent else colors.border,
                                shape,
                            ).clickable {
                                if (selecting) {
                                    selected = if (isChosen) chosen - page.id else chosen + page.id
                                } else {
                                    actions.onGo(page.id)
                                }
                            }.semantics {
                                contentDescription = "Page ${index + 1}"
                                this.selected = highlighted
                            },
                    ) {
                        thumbnail(page, Modifier.fillMaxWidth())
                        if (!selecting) {
                            OverviewMenu(page.id, pages.size == 1, actions, Modifier.align(Alignment.TopEnd))
                        }
                    }
                    Text(
                        text = (index + 1).toString(),
                        style = FolioTheme.type.labelSmall,
                        color = if (highlighted) colors.accent else colors.textSecondary,
                        modifier = Modifier.padding(top = space.s4),
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewMenu(
    page: PageId,
    onlyPage: Boolean,
    actions: PageActions,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        FolioIconButton(FolioIcons.Ellipsis, "Page menu", onClick = { open = true })
        PageMenu(open, { open = false }, page, onlyPage, actions)
    }
}
