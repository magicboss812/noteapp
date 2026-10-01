package dev.folio.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.ColorDot
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.FolioPopover
import dev.folio.core.designsystem.component.SegmentedTabs
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.model.Orientation
import dev.folio.core.model.PaperSize
import dev.folio.core.model.TemplateKind
import dev.folio.feature.editor.state.ApplyTo
import dev.folio.feature.editor.state.PageSettings
import dev.folio.feature.editor.state.PaperColor
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

private val SHEET_WIDTH = 440.dp
private val SHEET_MAX_HEIGHT = 620.dp
private val TEMPLATE_KINDS =
    listOf(
        TemplateKind.BLANK to "Blank",
        TemplateKind.LINED to "Lined",
        TemplateKind.GRID to "Grid",
        TemplateKind.DOTTED to "Dotted",
    )
private const val MM_PER_PT = 25.4f / 72f

/**
 * Page settings sheet (10-editor-ui.md#pages): size, orientation, template and spacing, paper color. [initial] is
 * what the page has now; Apply hands the edited settings and the scope to [onApply]. A tap outside closes it.
 */
@Composable
internal fun PageSettingsSheet(
    initial: PageSettings,
    onApply: (PageSettings, ApplyTo) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var settings by remember(initial) { mutableStateOf(initial) }
    var applyTo by remember { mutableStateOf(ApplyTo.THIS_PAGE) }
    Box(
        modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        FolioPopover(
            title = "Page settings",
            // Taps inside the card must not close it.
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
            width = SHEET_WIDTH,
            onClose = onClose,
        ) {
            Column(
                Modifier.heightIn(max = SHEET_MAX_HEIGHT).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s16),
            ) {
                SizeSection(settings, { settings = it })
                TemplateSection(settings, { settings = it })
                PaperSection(settings, { settings = it })
            }
            SegmentedTabs(
                tabs = ApplyTo.entries.map { it.label }.toImmutableList(),
                selectedIndex = applyTo.ordinal,
                onSelect = { applyTo = ApplyTo.entries[it] },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s8, Alignment.End)) {
                FolioButton("Cancel", onClose, style = FolioButtonStyle.Text)
                FolioButton("Apply", { onApply(settings, applyTo) })
            }
        }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s8)) {
        Text(title, style = FolioTheme.type.label, color = FolioTheme.colors.textSecondary)
        content()
    }
}

@Composable
private fun SizeSection(
    settings: PageSettings,
    onChange: (PageSettings) -> Unit,
) {
    Section("Size") {
        val sizes = PaperSize.entries
        SegmentedTabs(
            tabs = sizes.map { it.name }.toImmutableList(),
            selectedIndex = sizes.indexOf(settings.size),
            onSelect = { if (settings.sizeEditable) onChange(settings.copy(size = sizes[it])) },
            modifier = Modifier.fillMaxWidth(),
        )
        SegmentedTabs(
            tabs = persistentListOf("Portrait", "Landscape"),
            selectedIndex = settings.orientation.ordinal,
            onSelect = { if (settings.sizeEditable) onChange(settings.copy(orientation = Orientation.entries[it])) },
            modifier = Modifier.fillMaxWidth(),
        )
        val widthMm = settings.size.widthPt * MM_PER_PT
        val heightMm = settings.size.heightPt * MM_PER_PT
        val (w, h) = if (settings.orientation == Orientation.PORTRAIT) widthMm to heightMm else heightMm to widthMm
        Text(
            text =
                if (settings.sizeEditable) {
                    "${w.toInt()} x ${h.toInt()} mm"
                } else {
                    "This page keeps its size (PDF or infinite page)."
                },
            style = FolioTheme.type.caption,
            color = FolioTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun TemplateSection(
    settings: PageSettings,
    onChange: (PageSettings) -> Unit,
) {
    Section("Template") {
        val kinds = TEMPLATE_KINDS
        // Kinds that are not offered here (Cornell, planner, custom) show no tab selected.
        SegmentedTabs(
            tabs = kinds.map { it.second }.toImmutableList(),
            selectedIndex = kinds.indexOfFirst { it.first == settings.template },
            onSelect = { onChange(settings.withTemplate(kinds[it].first)) },
            modifier = Modifier.fillMaxWidth(),
        )
        val choices = settings.spacingChoices()
        if (choices.isNotEmpty()) {
            Text("Spacing", style = FolioTheme.type.caption, color = FolioTheme.colors.textTertiary)
            SegmentedTabs(
                tabs = choices.map { "%.1f mm".format(it * MM_PER_PT) }.toImmutableList(),
                selectedIndex = choices.indexOfFirst { kotlin.math.abs(it - settings.spacingPt) < SPACING_EPSILON },
                onSelect = { onChange(settings.copy(spacingPt = choices[it])) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (settings.hasMarginLine) {
            SegmentedTabs(
                tabs = persistentListOf("Margin line", "No margin line"),
                selectedIndex = if (settings.marginLine) 0 else 1,
                onSelect = { onChange(settings.copy(marginLine = it == 0)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private const val SPACING_EPSILON = 0.01f

@Composable
private fun PaperSection(
    settings: PageSettings,
    onChange: (PageSettings) -> Unit,
) {
    Section("Paper color") {
        Row(horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s12), verticalAlignment = Alignment.CenterVertically) {
            PaperColor.entries.forEach { paper ->
                ColorDot(
                    color = Color(paper.argb),
                    contentDescription = paper.label,
                    selected = paper.argb == settings.paperArgb,
                    onClick = { onChange(settings.copy(paperArgb = paper.argb)) },
                )
            }
        }
    }
}
