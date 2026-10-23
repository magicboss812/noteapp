package dev.folio.core.format.container

import com.google.common.truth.Truth.assertThat
import dev.folio.core.format.manifest.FolioJson
import dev.folio.core.format.manifest.Manifest
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.testing.ModelFixtures
import org.junit.Test

class DocumentLinksTest {
    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"

    @Test
    fun targets_noteLinksInEveryFlow_distinctInTextOrder() {
        val doc =
            ModelFixtures.document(
                listOf(ModelFixtures.page("p")),
                flows =
                    listOf(
                        ModelFixtures.flow("f1", "See [A](folio://doc/$a) and [B](folio://doc/$b#page=p2)."),
                        ModelFixtures.flow("f2", "- [again](folio://doc/$a \"title\")\n[web](https://example.org)"),
                    ),
            )

        assertThat(DocumentLinks.targets(doc)).containsExactly(a, b).inOrder()
    }

    @Test
    fun targets_linkToItself_isLeftOut() {
        val doc =
            ModelFixtures.document(
                listOf(ModelFixtures.page("p")),
                flows = listOf(ModelFixtures.flow("f", "[me](folio://doc/00000000-0000-4000-8000-000000000001)")),
            )

        assertThat(DocumentLinks.targets(doc)).isEmpty()
    }

    @Test
    fun encodeManifest_listsTheNoteLinks() {
        val doc =
            ModelFixtures.document(
                listOf(ModelFixtures.page("p")),
                flows = listOf(ModelFixtures.flow("f", "[A](folio://doc/$a)")),
            )

        val bytes = DocumentEntries.encodeManifest(doc, ManifestApp("Folio", "test"))
        val manifest = FolioJson.decodeFromString(Manifest.serializer(), bytes.decodeToString())

        assertThat(manifest.links).containsExactly(a)
    }
}
