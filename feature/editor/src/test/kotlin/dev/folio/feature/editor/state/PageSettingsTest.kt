package dev.folio.feature.editor.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.AssetId
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.PdfBackground
import dev.folio.core.model.TemplateKind
import dev.folio.core.testing.ModelFixtures
import org.junit.Test

class PageSettingsTest {
    private val doc =
        ModelFixtures.document(listOf(ModelFixtures.page("p1"), ModelFixtures.page("p2"), ModelFixtures.page("p3")))
    private val first = PageId("p1")
    private val current = PageSettings(doc.pageRef(first)!!)

    @Test
    fun ofPage_readsSizeTemplateAndMargin() {
        assertThat(current.size).isEqualTo(PaperSize.A4)
        assertThat(current.orientation).isEqualTo(Orientation.PORTRAIT)
        assertThat(current.sizeEditable).isTrue()
        assertThat(current.template).isEqualTo(TemplateKind.LINED)
        assertThat(current.marginLine).isTrue()
    }

    @Test
    fun commandFor_unchanged_isNull() {
        ApplyTo.entries.forEach { assertThat(current.commandFor(doc, first, it)).isNull() }
    }

    @Test
    fun commandFor_thisPage_changesOnlyThatPageAndUndoes() {
        val change = current.copy(paperArgb = PaperColor.CREAM.argb, orientation = Orientation.LANDSCAPE)
        val applied = change.commandFor(doc, first, ApplyTo.THIS_PAGE)!!.execute(doc)
        assertThat(
            applied.doc
                .pageRef(first)!!
                .background.paperArgb,
        ).isEqualTo(PaperColor.CREAM.argb)
        assertThat(applied.doc.pageRef(first)!!.spec).isEqualTo(PageSpec.Fixed(PaperSize.A4, Orientation.LANDSCAPE))
        assertThat(applied.doc.pageRef(PageId("p2"))).isEqualTo(doc.pageRef(PageId("p2")))
        assertThat(applied.inverse.execute(applied.doc).doc).isEqualTo(doc)
    }

    @Test
    fun commandFor_allPages_changesEveryPageInOneStep() {
        val change = current.withTemplate(TemplateKind.GRID)
        val applied = change.commandFor(doc, first, ApplyTo.ALL_PAGES)!!.execute(doc)
        assertThat(
            applied.doc.pages
                .map { it.background.template.kind }
                .toSet(),
        ).containsExactly(TemplateKind.GRID)
        assertThat(applied.inverse.execute(applied.doc).doc).isEqualTo(doc)
    }

    @Test
    fun commandFor_newPages_updatesOnlyTheDefaults() {
        val change = current.copy(size = PaperSize.A3, paperArgb = PaperColor.LIGHT_GREEN.argb)
        val applied = change.commandFor(doc, first, ApplyTo.NEW_PAGES)!!.execute(doc)
        assertThat(applied.doc.meta.defaultPageSpec).isEqualTo(PageSpec.Fixed(PaperSize.A3, Orientation.PORTRAIT))
        assertThat(applied.doc.meta.defaultBackground.paperArgb).isEqualTo(PaperColor.LIGHT_GREEN.argb)
        assertThat(applied.doc.pages).isEqualTo(doc.pages)
    }

    @Test
    fun withTemplate_takesPresetSpacingAndMarginOfTheKind() {
        val grid = current.withTemplate(TemplateKind.GRID)
        assertThat(grid.marginLine).isFalse()
        assertThat(grid.hasMarginLine).isFalse()
        assertThat(grid.spacingChoices()).hasSize(3)
        assertThat(current.withTemplate(TemplateKind.BLANK).spacingChoices()).isEmpty()
    }

    @Test
    fun specFor_pdfPage_keepsItsSizeAndIsNotEditable() {
        val pdf = ModelFixtures.page("p1").let { it.copy(background = it.background.copy(pdf = PdfBackground(AssetId("a"), 0))) }
        val ref = ModelFixtures.document(listOf(pdf)).pageRef(first)!!
        val settings = PageSettings(ref)
        assertThat(settings.sizeEditable).isFalse()
        assertThat(settings.copy(size = PaperSize.A3).specFor(ref)).isEqualTo(ref.spec)
    }
}
