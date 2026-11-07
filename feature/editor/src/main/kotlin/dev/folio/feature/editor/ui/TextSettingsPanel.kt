package dev.folio.feature.editor.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.SegmentedTabs
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.text.fonts.BodySize
import dev.folio.core.text.fonts.FontGroup
import dev.folio.core.text.fonts.TextFontFamily
import dev.folio.core.text.fonts.TextSizing
import dev.folio.feature.editor.state.TextOptions
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.roundToInt

private const val PERCENT = 100

// Free size slider: the whole free range (A-039) in TextOptions.SIZE_STEP steps.
private val SIZE_STEPS = ((TextSizing.MAX_BODY_CAP - TextSizing.MIN_BODY_CAP) / TextOptions.SIZE_STEP).roundToInt() - 1

/**
 * Text tool settings (10-editor-ui.md#tool-options): body size presets and the free size slider, whose value
 * shows the line box height in grid lines, then the font families grouped as in [FontGroup], each shown in its
 * own face, and "Import font". The choices apply to text the tool creates next.
 */
@Suppress("MultipleEmitters") // Sections of the panel's scrolling column.
@Composable
internal fun TextSettings(
    text: TextOptions,
    fonts: ImmutableList<TextFontFamily>,
    onChange: OptionsChange,
    onImportFont: () -> Unit,
) {
    SectionLabel("Size")
    SegmentedTabs(
        tabs = SIZE_LABELS,
        selectedIndex = text.preset?.ordinal ?: -1,
        onSelect = { index -> onChange { it.copy(text = it.text.withBodyCap(BodySize.entries[index].capFraction)) } },
    )
    LabeledSlider(
        label = "Free size",
        valueText = sizeText(text),
        value = text.bodyCap,
        onValueChange = { cap -> onChange { it.copy(text = it.text.withBodyCap(cap)) } },
        valueRange = TextSizing.MIN_BODY_CAP..TextSizing.MAX_BODY_CAP,
        steps = SIZE_STEPS,
    )
    FontGroup.entries.forEach { group ->
        val members = fonts.filter { it.group == group }
        if (members.isNotEmpty() || group == FontGroup.IMPORTED) {
            PanelDivider()
            SectionLabel(group.label)
        }
        members.forEach { family ->
            FontRow(family, selected = family.name.equals(text.fontFamily, ignoreCase = true)) {
                onChange { it.copy(text = it.text.copy(fontFamily = family.name)) }
            }
        }
    }
    if (fonts.none { it.group == FontGroup.IMPORTED }) {
        Text("No imported fonts yet.", style = FolioTheme.type.caption, color = FolioTheme.colors.textSecondary)
    }
    FolioButton(label = "Import font", onClick = onImportFont, style = FolioButtonStyle.Text)
}

// "120% · 2 lines": the size relative to M and the body line box height.
private fun sizeText(text: TextOptions): String {
    val percent = (text.bodyCap / BodySize.M.capFraction * PERCENT).roundToInt()
    val lines = text.bodyLines
    return "$percent% · $lines ${if (lines == 1) "line" else "lines"}"
}

// One family, named in its own face; 44 dp tall, the selected one checked.
@Composable
private fun FontRow(
    family: TextFontFamily,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = FolioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FolioTheme.space.touchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            family.name,
            style = FolioTheme.type.body,
            fontFamily = family.fontFamily,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(FolioIcons.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(FolioTheme.space.iconToolbar))
        }
    }
}

private val FontGroup.label: String
    get() =
        when (this) {
            FontGroup.SANS -> "Sans serif"
            FontGroup.SERIF -> "Serif"
            FontGroup.MONO -> "Monospace"
            FontGroup.HANDWRITING -> "Handwriting"
            FontGroup.IMPORTED -> "Imported"
        }
