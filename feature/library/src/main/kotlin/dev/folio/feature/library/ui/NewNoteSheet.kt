package dev.folio.feature.library.ui

import android.graphics.RectF
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.ColorDot
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.component.FolioPopover
import dev.folio.core.designsystem.component.SegmentedTabs
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.TemplateKind
import dev.folio.core.render.template.TemplateRenderer
import dev.folio.feature.library.state.NEW_NOTE_TEMPLATES
import dev.folio.feature.library.state.NewNoteForm
import dev.folio.feature.library.state.NotePageType
import dev.folio.feature.library.state.NotePaper
import dev.folio.feature.library.state.galleryLabel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

private val SHEET_WIDTH = 640.dp

// The sheet's title bar, buttons and the margin around it take this much of the screen height.
private val SHEET_CHROME_HEIGHT = 150.dp
private val PREVIEW_HEIGHT = 108.dp
private val SELECTED_BORDER = 2.dp
private const val MM_PER_PT = 25.4f / 72f
private const val SPACING_EPSILON = 0.01f

/**
 * New-note sheet (10-editor-ui.md#new-note): title, paper size and orientation, page type, template gallery
 * with live previews (template at the chosen spacing on the chosen paper), spacing, paper color. A tap outside
 * closes it; [error] shows a failed create.
 */
@Composable
fun NewNoteSheet(
    form: NewNoteForm,
    onChange: (NewNoteForm) -> Unit,
    onCreate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    creating: Boolean = false,
    error: String? = null,
    width: Dp = SHEET_WIDTH,
) {
    Box(
        modifier.fillMaxSize().background(FolioTheme.colors.scrim).pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        FolioPopover(
            title = "New note",
            // Taps inside the card must not close it.
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
            width = width,
            onClose = onClose,
        ) {
            Column(
                Modifier
                    .heightIn(
                        max = (LocalConfiguration.current.screenHeightDp.dp - SHEET_CHROME_HEIGHT),
                    ).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s16),
            ) {
                TitleField(form.title) { onChange(form.copy(title = it)) }
                PaperSection(form, onChange)
                TemplateSection(form, onChange)
                PaperColorSection(form, onChange)
            }
            if (error != null) Text(error, style = FolioTheme.type.caption, color = FolioTheme.colors.danger)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s8, Alignment.End)) {
                FolioButton("Cancel", onClose, style = FolioButtonStyle.Text)
                FolioButton("Create", onCreate, enabled = !creating)
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
private fun TitleField(
    title: String,
    onChange: (String) -> Unit,
) {
    val colors = FolioTheme.colors
    Section("Title") {
        BasicTextField(
            value = title,
            onValueChange = onChange,
            singleLine = true,
            textStyle = FolioTheme.type.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = FolioTheme.space.touchTarget)
                    .background(colors.surfaceInset, FolioTheme.shapes.field)
                    .border(BorderStroke(1.dp, colors.border), FolioTheme.shapes.field)
                    .padding(horizontal = FolioTheme.space.s12)
                    .semantics { contentDescription = "Note title" },
            decorationBox = { inner ->
                Box(Modifier.heightIn(min = FolioTheme.space.touchTarget), contentAlignment = Alignment.CenterStart) { inner() }
            },
        )
    }
}

@Composable
private fun PaperSection(
    form: NewNoteForm,
    onChange: (NewNoteForm) -> Unit,
) {
    Section("Paper") {
        val sizes = PaperSize.entries
        Row(horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s8)) {
            SegmentedTabs(
                tabs = sizes.map { it.name }.toImmutableList(),
                selectedIndex = sizes.indexOf(form.size),
                onSelect = { onChange(form.copy(size = sizes[it])) },
                modifier = Modifier.weight(1f),
            )
            SegmentedTabs(
                tabs = persistentListOf("Portrait", "Landscape"),
                selectedIndex = form.orientation.ordinal,
                onSelect = { onChange(form.copy(orientation = Orientation.entries[it])) },
                modifier = Modifier.weight(1f),
            )
        }
        val types = NotePageType.entries
        SegmentedTabs(
            tabs = types.map { it.label }.toImmutableList(),
            selectedIndex = types.indexOf(form.pageType),
            onSelect = { onChange(form.copy(pageType = types[it])) },
            modifier = Modifier.fillMaxWidth(),
        )
        val spec = form.pageSpec()
        Text(
            text =
                "${(spec.widthPt * MM_PER_PT).toInt()} x ${(spec.heightPt * MM_PER_PT).toInt()} mm" +
                    if (form.pageType == NotePageType.INFINITE) ", grows in every direction" else "",
            style = FolioTheme.type.caption,
            color = FolioTheme.colors.textTertiary,
        )
    }
}

@Composable
private fun TemplateSection(
    form: NewNoteForm,
    onChange: (NewNoteForm) -> Unit,
) {
    Section("Template") {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s12),
        ) {
            NEW_NOTE_TEMPLATES.forEach { kind ->
                TemplateCard(form, kind) { onChange(form.withTemplate(kind)) }
            }
        }
        val choices = form.spacingChoices()
        if (choices.isNotEmpty()) {
            Text("Spacing", style = FolioTheme.type.caption, color = FolioTheme.colors.textTertiary)
            SegmentedTabs(
                tabs = choices.map { "%.1f mm".format(it * MM_PER_PT) }.toImmutableList(),
                selectedIndex = choices.indexOfFirst { kotlin.math.abs(it - form.spacingPt) < SPACING_EPSILON },
                onSelect = { onChange(form.copy(spacingPt = choices[it])) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun TemplateCard(
    form: NewNoteForm,
    kind: TemplateKind,
    onClick: () -> Unit,
) {
    val colors = FolioTheme.colors
    val selected = kind == form.template
    val shape = FolioTheme.shapes.tile
    // The selected card previews the chosen spacing, the others their defaults.
    val shown = if (selected) form else form.withTemplate(kind)
    Column(
        Modifier
            .clickable(onClickLabel = "Use ${kind.galleryLabel()} template", onClick = onClick)
            .padding(FolioTheme.space.s4),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s4),
    ) {
        TemplatePreview(
            form = shown,
            modifier =
                Modifier
                    .height(PREVIEW_HEIGHT)
                    .border(BorderStroke(if (selected) SELECTED_BORDER else 1.dp, if (selected) colors.accent else colors.border), shape),
        )
        Text(
            kind.galleryLabel(),
            style = FolioTheme.type.labelSmall,
            color = if (selected) colors.accent else colors.textSecondary,
        )
    }
}

/** The first page of a note made from [form]: paper color and template drawn by the page renderer's template painter. */
@Composable
internal fun TemplatePreview(
    form: NewNoteForm,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { TemplateRenderer() }
    val region = remember { RectF() }
    val fixed = PageSpec.Fixed(form.size, form.orientation)
    val spec = if (form.pageType == NotePageType.INFINITE) PageSpec.Infinite(fixed) else fixed
    val template = form.background().template
    val aspect = fixed.widthPt / fixed.heightPt
    Canvas(modifier.width(PREVIEW_HEIGHT * aspect)) {
        val scale = size.height / fixed.heightPt
        drawRect(Color(form.paperArgb))
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            native.save()
            native.scale(scale, scale)
            region.set(0f, 0f, fixed.widthPt, fixed.heightPt)
            renderer.draw(native, template, spec, region, scale)
            native.restore()
        }
    }
}

@Composable
private fun PaperColorSection(
    form: NewNoteForm,
    onChange: (NewNoteForm) -> Unit,
) {
    Section("Paper color") {
        Row(horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s12), verticalAlignment = Alignment.CenterVertically) {
            NotePaper.entries.forEach { paper ->
                ColorDot(
                    color = Color(paper.argb),
                    contentDescription = paper.label,
                    selected = paper.argb == form.paperArgb,
                    onClick = { onChange(form.copy(paperArgb = paper.argb)) },
                )
            }
        }
    }
}
