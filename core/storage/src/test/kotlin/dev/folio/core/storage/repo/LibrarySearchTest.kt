package dev.folio.core.storage.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.container.DocumentEntries
import dev.folio.core.format.container.FolioContainerWriter
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.testing.ModelFixtures
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class LibrarySearchTest : RepositoryTestBase() {
    private val random = Random(5)

    @Test
    fun search_titleHitBeatsNewerBodyHit_andSnippetHighlightsTheMatch() =
        runTest {
            writeDoc("Body.folio", "Lecture", "today we study the velocity of a falling body", modifiedMs = 2_000)
            writeDoc("Title.folio", "Velocity basics", "unrelated words", modifiedMs = 1_000)
            scanner.fullScan()

            val hits = library.search("velo").first()

            assertThat(hits.map { it.doc.path }).containsExactly("Title.folio", "Body.folio").inOrder()
            val body = hits[1].snippet
            assertThat(body.ranges.map { body.text.substring(it.first, it.last + 1) }).containsExactly("velocity")
            val title = hits[0].title
            assertThat(title.ranges.map { title.text.substring(it.first, it.last + 1) }).containsExactly("Velocity")
        }

    @Test
    fun search_folderAndTagFilters_limitTheResults() =
        runTest {
            writeDoc("Physics/Waves.folio", "Waves", "frequency and amplitude", tags = setOf("school"))
            writeDoc("Physics/Optics/Lenses.folio", "Lenses", "frequency of light")
            writeDoc("Physics2/Other.folio", "Other", "frequency")
            writeDoc("Home.folio", "Home", "frequency at home", tags = setOf("school"))
            scanner.fullScan()

            suspend fun paths(
                folder: String? = null,
                tag: String? = null,
            ) = library.search("freq", folder, tag).first().map { it.doc.path }

            assertThat(paths()).hasSize(4)
            assertThat(paths(folder = "Physics")).containsExactly("Physics/Waves.folio", "Physics/Optics/Lenses.folio")
            assertThat(paths(tag = "school")).containsExactly("Physics/Waves.folio", "Home.folio")
            assertThat(paths(folder = "Physics", tag = "school")).containsExactly("Physics/Waves.folio")
            assertThat(paths(folder = "")).hasSize(4)
        }

    @Test
    fun search_500Documents_under100Ms() =
        runTest {
            repeat(500) { i -> writeDoc("Folder ${i % 10}/Doc $i.folio", "Doc $i", "topic$i kinematics lecture about velocity") }
            scanner.fullScan()
            library.search("kinem velo").first() // warm up the statement cache

            val start = System.nanoTime()
            val hits = library.search("kinem velo").first()
            val ms = (System.nanoTime() - start) / 1_000_000
            println("search over 500 documents (500 hits): $ms ms")

            assertThat(hits).hasSize(500)
            assertThat(ms).isLessThan(100)
        }

    private fun writeDoc(
        path: String,
        title: String,
        text: String,
        tags: Set<String> = emptySet(),
        modifiedMs: Long = 0,
    ) {
        val base =
            ModelFixtures.document(
                pages = listOf(ModelFixtures.randomPage(random, 2)),
                flows = listOf(ModelFixtures.flow(ModelFixtures.randomId(random), text)),
                id = ModelFixtures.randomId(random),
            )
        val doc =
            base.copy(
                meta =
                    base.meta.copy(
                        title = title,
                        tags = persistentSetOf<String>().addAll(tags),
                        modifiedMs = modifiedMs,
                    ),
            )
        if ('/' in path) fs.mkdirs(path.substringBeforeLast('/'))
        val result = fs.writeAtomic(path) { out -> FolioContainerWriter.write(out, DocumentEntries.all(doc, ManifestApp(), emptyMap())) }
        check(result is Outcome.Success) { result }
    }
}
