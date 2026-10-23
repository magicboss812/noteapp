package dev.folio.feature.editor.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.PillGroup
import dev.folio.core.designsystem.component.PillOrientation
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.state.NoteChoice
import kotlinx.collections.immutable.ImmutableList

// Longer titles are cut in the picker and the menu.
private const val TITLE_CHARS = 40

// The picker as an inner rail (left or right dock): titles wrap instead of widening it.
private val PICKER_RAIL_WIDTH = 160.dp

private fun shortTitle(title: String): String =
    when {
        title.isBlank() -> "Untitled"
        title.length <= TITLE_CHARS -> title
        else -> title.take(TITLE_CHARS - 1).trimEnd() + "…"
    }

/**
 * The `[[` picker (07-text-engine.md#block-styles-as-built): row 2 while the caret is in a typed `[[query`, the
 * matching notes as buttons; picking one turns the query into a link to it.
 */
@Composable
internal fun NoteLinkRow(
    choices: ImmutableList<NoteChoice>,
    onPick: (NoteChoice) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val orientation = if (vertical) PillOrientation.Vertical else PillOrientation.Horizontal
    val shape = if (vertical) FolioTheme.shapes.popover else FolioTheme.shapes.stadium
    PillGroup(modifier, orientation = orientation, shape = shape) {
        val scroll = rememberScrollState()
        val content: @Composable () -> Unit = {
            Text(
                text = if (choices.isEmpty()) "No matching notes" else "Link to",
                style = FolioTheme.type.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = space.s12, vertical = if (vertical) space.s8 else 0.dp),
            )
            choices.forEach { choice ->
                FolioButton(shortTitle(choice.title), { onPick(choice) }, style = FolioButtonStyle.Text, textColor = colors.textPrimary)
            }
        }
        if (vertical) {
            Column(
                Modifier.width(PICKER_RAIL_WIDTH).edgeFade(scroll, SCROLL_FADE, vertical = true).verticalScroll(scroll),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { content() }
        } else {
            Row(
                Modifier.heightIn(min = space.toolbarPill).edgeFade(scroll, SCROLL_FADE).horizontalScroll(scroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                content()
            }
        }
    }
}

/** The More menu's Linked from list: notes that link to this one ([notes]; open while not null). */
@Composable
internal fun LinkedFromMenu(
    notes: ImmutableList<NoteChoice>?,
    onDismiss: () -> Unit,
    onOpen: (NoteChoice) -> Unit,
) {
    val colors = FolioTheme.colors
    DropdownMenu(
        expanded = notes != null,
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        shape = FolioTheme.shapes.menu,
    ) {
        val label: @Composable (String, Boolean) -> Unit = { text, enabled ->
            Text(text, style = FolioTheme.type.body, color = if (enabled) colors.textPrimary else colors.textSecondary)
        }
        DropdownMenuItem(text = { label("Linked from", false) }, onClick = {}, enabled = false)
        if (notes.isNullOrEmpty()) {
            DropdownMenuItem(text = { label("No notes link here", false) }, onClick = {}, enabled = false)
        } else {
            notes.forEach { note -> DropdownMenuItem(text = { label(shortTitle(note.title), true) }, onClick = { onOpen(note) }) }
        }
    }
}
