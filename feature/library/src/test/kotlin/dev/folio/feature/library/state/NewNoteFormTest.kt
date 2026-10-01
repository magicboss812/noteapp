package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.TemplateKind
import dev.folio.core.render.template.TemplatePresets
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class NewNoteFormTest {
    @Test
    fun defaultTitle_usesTheDayOfTheClock() {
        val nowMs = Instant.parse("2026-10-02T23:30:00Z").toEpochMilli()
        assertThat(NewNoteForm.defaultTitle(nowMs, ZoneOffset.UTC)).isEqualTo("Untitled 2026-10-02")
        assertThat(NewNoteForm.defaultTitle(nowMs, ZoneOffset.ofHours(2))).isEqualTo("Untitled 2026-10-03")
    }

    @Test
    fun toSpec_fixedPaperAndTemplate() {
        val form =
            NewNoteForm(
                title = "  Physics  ",
                size = PaperSize.A5,
                orientation = Orientation.LANDSCAPE,
                template = TemplateKind.GRID,
                spacingPt = TemplatePresets.LATTICE_SPACINGS_PT[0],
                paperArgb = NotePaper.CREAM.argb,
            )

        val spec = form.toSpec("perf", "Untitled")

        assertThat(spec.folder).isEqualTo("perf")
        assertThat(spec.title).isEqualTo("Physics")
        assertThat(spec.pageSpec).isEqualTo(PageSpec.Fixed(PaperSize.A5, Orientation.LANDSCAPE))
        assertThat(spec.background.paperArgb).isEqualTo(NotePaper.CREAM.argb)
        assertThat(spec.background.template.kind).isEqualTo(TemplateKind.GRID)
        assertThat(spec.background.template.spacingPt).isEqualTo(TemplatePresets.LATTICE_SPACINGS_PT[0])
        assertThat(spec.background.pdf).isNull()
    }

    @Test
    fun toSpec_blankTitleFallsBack() {
        assertThat(NewNoteForm("   ").toSpec("", "Untitled 2026-10-02").title).isEqualTo("Untitled 2026-10-02")
    }

    @Test
    fun toSpec_infiniteKeepsThePaperAsOriginFrame() {
        val form = NewNoteForm("x", size = PaperSize.A3, pageType = NotePageType.INFINITE)

        assertThat(form.pageSpec()).isEqualTo(PageSpec.Infinite(PageSpec.Fixed(PaperSize.A3, Orientation.PORTRAIT)))
    }

    @Test
    fun withTemplate_takesTheDefaultSpacingOfTheKind() {
        val form = NewNoteForm("x").withTemplate(TemplateKind.DOTTED)

        assertThat(form.template).isEqualTo(TemplateKind.DOTTED)
        assertThat(form.spacingPt).isEqualTo(TemplatePresets.default(TemplateKind.DOTTED).spacingPt)
    }

    @Test
    fun background_templatesWithoutSpacingPresetsKeepTheirOwnGeometry() {
        val cornell = NewNoteForm("x", template = TemplateKind.CORNELL, spacingPt = 99f)

        assertThat(cornell.spacingChoices()).isEmpty()
        assertThat(cornell.background().template).isEqualTo(TemplatePresets.default(TemplateKind.CORNELL))
    }

    @Test
    fun spacingChoices_listsAStoredOddValueFirst() {
        val odd = NewNoteForm("x", template = TemplateKind.LINED, spacingPt = 30f)

        assertThat(odd.spacingChoices().first()).isEqualTo(30f)
        assertThat(odd.spacingChoices()).hasSize(TemplatePresets.LINED_SPACINGS_PT.size + 1)
    }

    @Test
    fun gallery_everyTemplateHasAPresetAndNoCustom() {
        assertThat(NEW_NOTE_TEMPLATES).doesNotContain(TemplateKind.CUSTOM)
        NEW_NOTE_TEMPLATES.forEach { assertThat(TemplatePresets.default(it).kind).isEqualTo(it) }
    }
}
