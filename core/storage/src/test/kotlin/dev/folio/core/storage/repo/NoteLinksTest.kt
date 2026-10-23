package dev.folio.core.storage.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.container.DocumentEntries
import dev.folio.core.format.container.FolioContainerWriter
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.testing.ModelFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteLinksTest : RepositoryTestBase() {
    private val links by lazy { NoteLinks(db.dao(), dispatchers) }

    @Test
    fun resolve_byDocId_followsRenamesAndMoves() =
        runTest {
            writeDoc("Target.folio", TARGET, "Target")
            scanner.fullScan()
            fs.mkdirs("Physics")
            documents.move("Target.folio", "Physics").orThrow()
            documents.rename("Physics/Target.folio", "Renamed").orThrow()

            val target = links.resolve(TARGET)

            assertThat(target).isEqualTo(LinkTarget(TARGET, "Physics/Renamed.folio", "Renamed"))
        }

    @Test
    fun resolve_missingOrBinnedTarget_isNull_andListedAsMissing() =
        runTest {
            writeDoc("Target.folio", TARGET, "Target")
            writeDoc("Other.folio", OTHER, "Other")
            scanner.fullScan()
            documents.delete("Other.folio").orThrow()

            assertThat(links.resolve(OTHER)).isNull()
            assertThat(links.resolve(UNKNOWN)).isNull()
            assertThat(links.missing(listOf(TARGET, OTHER, UNKNOWN))).containsExactly(OTHER, UNKNOWN)
        }

    @Test
    fun backlinks_documentsLinkingToTheTarget_byTitle() =
        runTest {
            writeDoc("Target.folio", TARGET, "Target", "Links to [myself](folio://doc/$TARGET)")
            writeDoc("B.folio", OTHER, "Beta", "See [the target](folio://doc/$TARGET).")
            writeDoc("A.folio", THIRD, "Alpha", "- [ ] read [Target](folio://doc/$TARGET#page=p1)")
            writeDoc("C.folio", UNKNOWN, "Gamma", "No links, only [web](https://example.org)")
            scanner.fullScan()

            val back = links.backlinks(TARGET)

            assertThat(back.map { it.title }).containsExactly("Alpha", "Beta").inOrder()
            assertThat(links.backlinks(OTHER)).isEmpty()
        }

    @Test
    fun titled_substringAnyCase_wildcardsAreLiteral() =
        runTest {
            writeDoc("Waves.folio", TARGET, "Waves and optics", modifiedMs = 1)
            writeDoc("Optics.folio", OTHER, "Optics 100%", modifiedMs = 2)
            writeDoc("Home.folio", THIRD, "Home")
            scanner.fullScan()

            assertThat(links.titled("OPTICS").map { it.title }).containsExactly("Optics 100%", "Waves and optics").inOrder()
            assertThat(links.titled("0%").map { it.title }).containsExactly("Optics 100%")
            assertThat(links.titled("_")).isEmpty()
            assertThat(links.titled(" ")).hasSize(3)
        }

    private fun writeDoc(
        path: String,
        id: String,
        title: String,
        text: String = "",
        modifiedMs: Long = 0,
    ) {
        val base =
            ModelFixtures.document(
                pages = listOf(ModelFixtures.page("p1")),
                flows = listOf(ModelFixtures.flow("f", text)),
                id = id,
            )
        val doc = base.copy(meta = base.meta.copy(title = title, modifiedMs = modifiedMs))
        val result = fs.writeAtomic(path) { out -> FolioContainerWriter.write(out, DocumentEntries.all(doc, ManifestApp(), emptyMap())) }
        check(result is Outcome.Success) { result }
    }

    private companion object {
        const val TARGET = "11111111-1111-4111-8111-111111111111"
        const val OTHER = "22222222-2222-4222-8222-222222222222"
        const val THIRD = "33333333-3333-4333-8333-333333333333"
        const val UNKNOWN = "44444444-4444-4444-8444-444444444444"
    }
}
