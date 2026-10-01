// Ink colors and widths below are sample content, not tokens.
@file:Suppress("MagicNumber")

package dev.folio.core.designsystem.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.ColorDot
import dev.folio.core.designsystem.component.FolderCard
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.FolioDialogContent
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.FolioIconButtonStyle
import dev.folio.core.designsystem.component.FolioPopover
import dev.folio.core.designsystem.component.FolioSheetContent
import dev.folio.core.designsystem.component.FolioSnackbar
import dev.folio.core.designsystem.component.NoteCard
import dev.folio.core.designsystem.component.NoteCardPaper
import dev.folio.core.designsystem.component.PillDivider
import dev.folio.core.designsystem.component.PillGroup
import dev.folio.core.designsystem.component.SegmentedTabs
import dev.folio.core.designsystem.component.SheetHandle
import dev.folio.core.designsystem.component.ToolButton
import dev.folio.core.designsystem.component.WidthChip
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolderColor
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.designsystem.theme.folioShadow
import kotlinx.collections.immutable.persistentListOf

private val LEFT_COLUMN_WIDTH = 560.dp
private val DIALOG_WIDTH = 340.dp
private val SHEET_WIDTH = 200.dp
private val FOLDER_SWATCH = 24.dp
private val InkSamples = listOf(Color(0xFF111827), Color(0xFF2563EB), Color(0xFFDC2626), Color(0xFF16A34A), Color(0xFFFDE68A))

/** Every design system component in its main states, for previews and the Roborazzi catalog test. */
@Composable
internal fun ComponentCatalog(modifier: Modifier = Modifier) {
    val space = FolioTheme.space
    Row(
        modifier = modifier.fillMaxSize().background(FolioTheme.colors.canvas).padding(space.s24),
        horizontalArrangement = Arrangement.spacedBy(space.s32),
    ) {
        Column(Modifier.width(LEFT_COLUMN_WIDTH), verticalArrangement = Arrangement.spacedBy(space.s16)) {
            Section("Icons (Lucide)") { IconGrid() }
            Section("PillGroup, ToolButton, PillDivider") { ToolbarSample() }
            Section("WidthChip (dot, ring), ColorDot") { OptionsSample() }
            Section("SegmentedTabs") {
                SegmentedTabs(persistentListOf("All", "Favorites", "Tags", "Bin"), selectedIndex = 0, onSelect = {})
            }
            Section("FolioIconButton, FolioButton") {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s8)) {
                    FolioIconButton(FolioIcons.Search, "Search", onClick = {}, style = FolioIconButtonStyle.Outlined)
                    FolioIconButton(FolioIcons.Settings, "Settings", onClick = {})
                    FolioIconButton(FolioIcons.Trash, "Delete", onClick = {}, enabled = false)
                    FolioButton("Create", onClick = {})
                    FolioButton("Cancel", onClick = {}, style = FolioButtonStyle.Text)
                    FolioButton("Clear page", onClick = {}, style = FolioButtonStyle.Text, destructive = true)
                }
            }
            Section("FolioSnackbar") { FolioSnackbar(message = "Page deleted", actionLabel = "Undo") }
            Section("Folder colors") {
                Row(horizontalArrangement = Arrangement.spacedBy(space.s4)) {
                    FolderColor.entries.forEach { Box(Modifier.size(FOLDER_SWATCH).background(it.color, FolioTheme.shapes.full)) }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(space.s20)) {
            CardSamples()
            Row(horizontalArrangement = Arrangement.spacedBy(space.s20)) {
                FolioPopover(title = "Pen", onClose = {}) {
                    Text("Popover content", style = FolioTheme.type.label, color = FolioTheme.colors.textSecondary)
                }
                SheetSample()
            }
            FolioDialogContent(
                title = "Delete this note?",
                confirmLabel = "Delete",
                onConfirm = {},
                onDismiss = {},
                modifier = Modifier.width(DIALOG_WIDTH),
                text = "It moves to the Bin for 30 days.",
                destructive = true,
            )
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s8)) {
        Text(text = title, style = FolioTheme.type.caption, color = FolioTheme.colors.textSecondary)
        content()
    }
}

@Composable
private fun IconGrid() {
    val space = FolioTheme.space
    FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s12), verticalArrangement = Arrangement.spacedBy(space.s8)) {
        FolioIcons.all.forEach { (name, icon) ->
            Icon(icon, contentDescription = name, modifier = Modifier.size(space.iconToolbar), tint = FolioTheme.colors.iconToolbar)
        }
    }
}

@Composable
private fun ToolbarSample() {
    Row(horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.chromeInset)) {
        PillGroup {
            FolioIconButton(FolioIcons.Undo2, "Undo", onClick = {})
            FolioIconButton(FolioIcons.Redo2, "Redo", onClick = {}, enabled = false)
        }
        PillGroup {
            ToolButton(FolioIcons.Pen, "Pen", selected = true, onClick = {})
            ToolButton(FolioIcons.Highlighter, "Highlighter", selected = false, onClick = {})
            ToolButton(FolioIcons.Eraser, "Eraser", selected = false, onClick = {})
            PillDivider()
            ToolButton(FolioIcons.Lasso, "Lasso", selected = false, onClick = {})
            ToolButton(FolioIcons.Type, "Text", selected = false, onClick = {})
            ToolButton(FolioIcons.Table, "Table", selected = false, onClick = {}, enabled = false)
        }
    }
}

@Composable
private fun OptionsSample() {
    Row(horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.chromeInset)) {
        PillGroup {
            listOf(1.dp, 2.dp, 4.dp).forEachIndexed { index, width ->
                WidthChip(width, "Width ${index + 1}", selected = index == 1, onClick = {})
            }
            PillDivider()
            InkSamples.forEachIndexed { index, color ->
                ColorDot(color, "Color ${index + 1}", selected = index == 0, onClick = {})
            }
            FolioIconButton(FolioIcons.CirclePlus, "Add color", onClick = {})
        }
        PillGroup {
            listOf(2.dp, 5.dp, 9.dp).forEachIndexed { index, width ->
                WidthChip(width, "Eraser size ${index + 1}", selected = index == 0, onClick = {}, ring = true)
            }
        }
    }
}

@Composable
private fun CardSamples() {
    val gap = Arrangement.spacedBy(FolioTheme.space.s24)
    Column(verticalArrangement = gap) {
        NoteCardSamples(gap)
        FolderCardSamples(gap)
    }
}

@Composable
private fun NoteCardSamples(gap: Arrangement.HorizontalOrVertical) {
    Row(horizontalArrangement = gap) {
        NoteCard(
            title = "Linear algebra, week 3",
            timestamp = "Today, 1:21 PM",
            onClick = {},
            favorite = true,
            tags = persistentListOf("math"),
            onOverflow = {},
        ) {
            NoteCardPaper {
                Text(
                    "Eigenvalues\n- det(A - tI) = 0\n- trace = sum",
                    style = FolioTheme.type.caption,
                    color = Color(0xFF2E2D2B),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        NoteCard(title = "Selected", timestamp = "Yesterday", onClick = {}, selected = true) { NoteCardPaper() }
    }
}

@Composable
private fun FolderCardSamples(gap: Arrangement.HorizontalOrVertical) {
    Row(horizontalArrangement = gap) {
        FolderCard(
            name = "Research",
            noteCount = 12,
            color = FolderColor.Yellow,
            onClick = {},
            latestTitle = "Reading list",
            latestPreview = "Ink rendering papers, tile caches and grid typography.",
        )
        FolderCard(name = "Courses", noteCount = 1, color = FolderColor.Teal, onClick = {})
    }
}

@Composable
private fun SheetSample() {
    val colors = FolioTheme.colors
    val shape = FolioTheme.shapes.sheet
    Box(
        Modifier
            .width(SHEET_WIDTH)
            .folioShadow(FolioTheme.elevation.popover, shape, colors.shadow)
            .background(colors.surface, shape),
    ) {
        Column {
            SheetHandle()
            FolioSheetContent(title = "Page") {
                Text("Template, size and color", style = FolioTheme.type.body, color = colors.textSecondary)
            }
        }
    }
}

@Preview(name = "Catalog light", widthDp = 1200, heightDp = 900)
@Composable
private fun ComponentCatalogLightPreview() {
    FolioTheme(darkTheme = false) { ComponentCatalog() }
}

@Preview(name = "Catalog dark", widthDp = 1200, heightDp = 900)
@Composable
private fun ComponentCatalogDarkPreview() {
    FolioTheme(darkTheme = true) { ComponentCatalog() }
}
