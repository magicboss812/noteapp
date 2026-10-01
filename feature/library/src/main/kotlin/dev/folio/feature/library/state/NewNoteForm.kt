package dev.folio.feature.library.state

import androidx.compose.runtime.Immutable
import dev.folio.core.model.Background
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.TemplateKind
import dev.folio.core.render.template.TemplatePresets
import dev.folio.core.storage.repo.NewDocumentSpec
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** Paper colors of the new-note sheet (10-editor-ui.md#new-note). */
enum class NotePaper(
    val label: String,
    val argb: Int,
) {
    WHITE("White", 0xFFFFFFFF.toInt()),
    CREAM("Cream", 0xFFFFF8E7.toInt()),
    LIGHT_GRAY("Light gray", 0xFFF1F2F4.toInt()),
    LIGHT_GREEN("Light green", 0xFFEAF5EA.toInt()),
    DARK("Dark", 0xFF2B2D31.toInt()),
}

/** Fixed-size pages or one infinite canvas. */
enum class NotePageType(
    val label: String,
) {
    FIXED("Fixed pages"),
    INFINITE("Infinite canvas"),
}

/** Templates the sheet offers, in gallery order (custom needs an imported asset, so it is not listed). */
val NEW_NOTE_TEMPLATES: List<TemplateKind> =
    listOf(
        TemplateKind.BLANK,
        TemplateKind.LINED,
        TemplateKind.GRID,
        TemplateKind.DOTTED,
        TemplateKind.CORNELL,
        TemplateKind.GRAPH_AXES,
        TemplateKind.MUSIC_STAFF,
        TemplateKind.PLANNER_DAILY,
        TemplateKind.PLANNER_WEEKLY,
    )

/** Display name of a template in the gallery. */
fun TemplateKind.galleryLabel(): String =
    when (this) {
        TemplateKind.BLANK -> "Blank"
        TemplateKind.LINED -> "Lined"
        TemplateKind.GRID -> "Grid"
        TemplateKind.DOTTED -> "Dotted"
        TemplateKind.CORNELL -> "Cornell"
        TemplateKind.GRAPH_AXES -> "Graph"
        TemplateKind.MUSIC_STAFF -> "Music"
        TemplateKind.PLANNER_DAILY -> "Daily"
        TemplateKind.PLANNER_WEEKLY -> "Weekly"
        TemplateKind.CUSTOM -> "Custom"
    }

/**
 * What the new-note sheet edits (10-editor-ui.md#new-note). [spacingPt] only applies to templates with spacing
 * presets; the other kinds keep their own geometry.
 */
@Immutable
data class NewNoteForm(
    val title: String,
    val size: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.PORTRAIT,
    val pageType: NotePageType = NotePageType.FIXED,
    val template: TemplateKind = TemplateKind.LINED,
    val spacingPt: Float = TemplatePresets.default(TemplateKind.LINED).spacingPt,
    val paperArgb: Int = NotePaper.WHITE.argb,
) {
    /** Same form with template [kind] and its default spacing. */
    fun withTemplate(kind: TemplateKind): NewNoteForm =
        if (kind == template) this else copy(template = kind, spacingPt = TemplatePresets.default(kind).spacingPt)

    /** Selectable spacings of the template, with a stored value that is not one of them first. */
    fun spacingChoices(): List<Float> {
        val presets = TemplatePresets.spacingsPt(template)
        return if (presets.isEmpty() || presets.any { abs(it - spacingPt) < SPACING_EPSILON }) presets else listOf(spacingPt) + presets
    }

    /** The page spec: the chosen paper, or an infinite canvas whose origin frame is that paper. */
    fun pageSpec(): PageSpec {
        val fixed = PageSpec.Fixed(size, orientation)
        return if (pageType == NotePageType.INFINITE) PageSpec.Infinite(fixed) else fixed
    }

    /** The background of every page: paper color and the template at the chosen spacing. */
    fun background(): Background {
        val preset = TemplatePresets.default(template)
        val spaced = if (TemplatePresets.spacingsPt(template).isEmpty()) preset else preset.copy(spacingPt = spacingPt)
        return Background(paperArgb, spaced, null)
    }

    /** The document to create in [folder] (library-relative, "" = root); a blank title falls back to [fallbackTitle]. */
    fun toSpec(
        folder: String,
        fallbackTitle: String,
    ): NewDocumentSpec = NewDocumentSpec(folder, title.trim().ifEmpty { fallbackTitle }, pageSpec(), background())

    companion object {
        private const val SPACING_EPSILON = 0.01f

        /** "Untitled YYYY-MM-DD" for the day of [nowMs] in [zone]. */
        fun defaultTitle(
            nowMs: Long,
            zone: ZoneId = ZoneId.systemDefault(),
        ): String = "Untitled ${Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()}"
    }
}
