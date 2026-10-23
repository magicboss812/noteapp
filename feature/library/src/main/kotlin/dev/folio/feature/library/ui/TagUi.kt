package dev.folio.feature.library.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.library.state.LibraryDocItem
import dev.folio.feature.library.state.LibraryTab
import dev.folio.feature.library.state.TagCount
import dev.folio.feature.library.state.addTag
import dev.folio.feature.library.state.normalizeTag
import dev.folio.feature.library.state.removeTag
import dev.folio.feature.library.state.tagCounts
import dev.folio.feature.library.state.tagSuggestions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

private val CHIP_HEIGHT = 36.dp
private val TAG_ROW_SIDE = 24.dp

/**
 * A tag as a stadium chip in a 44 dp target. [selected] fills it with `accentContainer`; [count] adds the number of
 * notes; [removable] turns the chip into a removable one (a close glyph, and a tap removes the tag).
 */
@Composable
internal fun TagChip(
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    count: Int? = null,
    removable: Boolean = false,
    clickLabel: String? = null,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val background = if (selected) colors.accentContainer else colors.surface
    val content = if (selected) colors.accent else colors.textSecondary
    // The 44 dp box takes the touch; the 36 dp chip inside is only the look.
    Box(
        modifier
            .heightIn(min = space.touchTarget)
            .clickable(role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = CHIP_HEIGHT)
                .clip(FolioTheme.shapes.stadium)
                .background(background)
                .border(space.borderWidth, if (selected) colors.accent else colors.border, FolioTheme.shapes.stadium)
                .padding(horizontal = space.s12),
            horizontalArrangement = Arrangement.spacedBy(space.s4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = "#$tag", style = FolioTheme.type.labelSmall, color = content, maxLines = 1)
            if (count != null) Text(text = count.toString(), style = FolioTheme.type.caption, color = colors.textTertiary)
            if (removable) {
                Icon(FolioIcons.X, contentDescription = null, modifier = Modifier.size(space.s12), tint = colors.textTertiary)
            }
        }
    }
}

/** Tags in use over all notes, and the one filtering the Tags tab. */
internal class TagFilter(
    val counts: ImmutableList<TagCount>,
    val known: ImmutableList<String>,
    val active: String?,
) {
    companion object {
        fun of(
            documents: List<LibraryDocItem>,
            chosen: String?,
        ): TagFilter {
            val counts = tagCounts(documents)
            // A tag nobody carries any more (last note untagged or deleted) stops filtering.
            val active = chosen?.takeIf { tag -> counts.any { it.tag == tag } }
            return TagFilter(counts, counts.map { it.tag }.sorted().toImmutableList(), active)
        }
    }
}

/** The chip row between the tabs and the notes; only the Tags tab has it, and only while some note is tagged. */
@Composable
internal fun TagTabChips(
    tab: LibraryTab,
    tags: TagFilter,
    onSelectTag: (String) -> Unit,
) {
    if (tab == LibraryTab.TAGS && tags.counts.isNotEmpty()) {
        TagChipRow(tags.counts, tags.active, onSelectTag, Modifier.padding(bottom = FolioTheme.space.s8))
    }
}

/** The Tags tab chips: every tag with its note count; the chosen one is filled, tapping it again clears the filter. */
@Composable
internal fun TagChipRow(
    tags: ImmutableList<TagCount>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = TAG_ROW_SIDE),
        horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s8),
    ) {
        items(tags, key = { it.tag }) { entry ->
            TagChip(
                tag = entry.tag,
                count = entry.count,
                selected = entry.tag == selected,
                clickLabel = if (entry.tag == selected) "Show all tags" else "Show notes tagged ${entry.tag}",
                onClick = { onSelect(entry.tag) },
            )
        }
    }
}

/**
 * Tag editor of the note [noteTitle]: its [initial] tags as removable chips, a field that adds typed tags, and
 * suggestions from the tags already in [known]. Save also takes a tag that was typed but not added yet.
 */
@Composable
internal fun TagEditorDialog(
    noteTitle: String,
    initial: ImmutableList<String>,
    known: ImmutableList<String>,
    onSave: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    var tags by remember { mutableStateOf<List<String>>(initial) }
    var text by rememberSaveable { mutableStateOf("") }
    val add = {
        tags = addTag(tags, text, known)
        text = ""
    }
    DialogFrame("Tags", onDismiss) {
        Text(text = noteTitle, style = FolioTheme.type.body, color = colors.textSecondary, maxLines = 1)
        if (tags.isEmpty()) {
            Text(text = "No tags yet.", style = FolioTheme.type.caption, color = colors.textTertiary)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s8)) {
                tags.forEach { tag ->
                    TagChip(tag, onClick = { tags = removeTag(tags, tag) }, removable = true, clickLabel = "Remove tag")
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(space.s4)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = FolioTheme.type.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { add() }),
                modifier =
                    Modifier
                        .weight(1f)
                        .background(colors.surfaceInset, FolioTheme.shapes.field)
                        .border(BorderStroke(space.borderWidth, colors.border), FolioTheme.shapes.field)
                        .padding(horizontal = space.s12)
                        .semantics { contentDescription = "New tag" },
                decorationBox = { inner ->
                    Box(Modifier.heightIn(min = space.touchTarget), contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text("Add a tag", style = FolioTheme.type.body, color = colors.textTertiary)
                        inner()
                    }
                },
            )
            FolioButton("Add", add, style = FolioButtonStyle.Text, enabled = normalizeTag(text) != null)
        }
        val suggestions = tagSuggestions(known, tags, text)
        if (suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(space.s8)) {
                suggestions.forEach { tag ->
                    TagChip(tag, onClick = { tags = addTag(tags, tag, known) }, clickLabel = "Add tag")
                }
            }
        }
        DialogButtons(
            confirmLabel = "Save",
            confirmEnabled = true,
            onConfirm = { onSave(addTag(tags, text, known)) },
            onDismiss = onDismiss,
        )
    }
}
