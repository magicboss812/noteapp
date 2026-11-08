package dev.folio.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.SegmentedTabs
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.model.FlowStyle
import dev.folio.core.text.fonts.BodySize
import dev.folio.core.text.fonts.FontGroup
import dev.folio.core.text.fonts.TextFontFamily
import dev.folio.core.text.fonts.TextSizing
import dev.folio.feature.editor.state.TextOptions
import kotlinx.collections.immutable.ImmutableList
import kotlin.math.min
import kotlin.math.roundToInt

private const val PERCENT = 100

// Free size slider: the whole free range (A-039) in TextOptions.SIZE_STEP steps.
private val SIZE_STEPS = ((TextSizing.MAX_BODY_CAP - TextSizing.MIN_BODY_CAP) / TextOptions.SIZE_STEP).roundToInt() - 1

// Lift slider: 0..0.35 U in 0.05 U steps (A-054).
private val LIFT_STEPS = (FlowStyle.MAX_BASELINE_LIFT / FlowStyle.BASELINE_LIFT_STEP).roundToInt() - 1

// Lift preview: one grid unit, three line boxes, the sample on the lower rule.
private val PREVIEW_UNIT = 28.dp
private const val PREVIEW_LINES = 3
private const val PREVIEW_TEXT = "Typed notes sit here, gjpqy"

// Largest body cap of a one-line box (07-text-engine.md#font-normalization) and a typical cap height / font size.
private const val ONE_LINE_CAP = 0.66f
private const val TYPICAL_CAP_RATIO = 0.7f

/**
 * Text tool settings (10-editor-ui.md#tool-options): body size presets and the free size slider, whose value
 * shows the line box height in grid lines, the distance above lines with a ruled preview (A-054), then the font
 * families grouped as in [FontGroup], each shown in its own face, and "Import font". The choices apply to text the
 * tool creates next.
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
    PanelDivider()
    LiftPreview(text, fonts.firstOrNull { it.name.equals(text.fontFamily, ignoreCase = true) }?.fontFamily ?: FontFamily.Default)
    LabeledSlider(
        label = "Distance above lines",
        valueText = "${(text.baselineLift * PERCENT).roundToInt()}% of a line",
        value = text.baselineLift,
        onValueChange = { lift -> onChange { it.copy(text = it.text.withBaselineLift(lift)) } },
        valueRange = 0f..FlowStyle.MAX_BASELINE_LIFT,
        steps = LIFT_STEPS,
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

// Sample text on two rules of paper, its baseline the chosen lift above the lower rule (A-054); the size follows the
// body size up to one line box so the lift stays readable.
@Composable
private fun LiftPreview(
    text: TextOptions,
    family: FontFamily,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val unitPx = with(density) { PREVIEW_UNIT.toPx() }
    val ink = colors.textPrimary
    val sample =
        remember(family, text.bodyCap, unitPx, ink) {
            val sizeSp = with(density) { (unitPx * min(text.bodyCap, ONE_LINE_CAP) / TYPICAL_CAP_RATIO).toSp() }
            measurer.measure(PREVIEW_TEXT, TextStyle(fontFamily = family, fontSize = sizeSp, color = ink), maxLines = 1)
        }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(PREVIEW_UNIT * PREVIEW_LINES)
            .clip(FolioTheme.shapes.tile)
            .background(colors.page)
            .border(space.borderWidth, colors.border, FolioTheme.shapes.tile)
            .semantics { contentDescription = "Text position preview" },
    ) {
        val stroke = space.borderWidth.toPx()
        for (i in 1 until PREVIEW_LINES) {
            drawLine(colors.textTertiary, Offset(0f, i * unitPx), Offset(size.width, i * unitPx), strokeWidth = stroke)
        }
        val baseline = (PREVIEW_LINES - 1) * unitPx - text.baselineLift * unitPx
        drawText(sample, topLeft = Offset(space.s16.toPx(), baseline - sample.firstBaseline))
    }
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
