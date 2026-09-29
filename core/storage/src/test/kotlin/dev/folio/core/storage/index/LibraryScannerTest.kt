package dev.folio.core.storage.index

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.container.DocumentEntries
import dev.folio.core.format.container.FolioContainerWriter
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.Document
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.testing.TempDirFolioFs
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class LibraryScannerTest {
    @get:Rule val fs = TempDirFolioFs()

    private val clock = FakeClock()
    private lateinit var db: IndexDb
    private lateinit var scanner: LibraryScanner
    private val random = Random(500)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDb::class.java).build()
        val io = kotlinx.coroutines.Dispatchers.IO
        scanner =
            LibraryScanner(
                fs,
                db,
                clock,
                dev.folio.core.common
                    .FolioDispatchers(io, io, io, io, io),
            )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun fullScan_500Documents_under3Seconds() =
        runTest {
            repeat(500) { i -> writeDoc("Folder ${i % 10}/Doc $i.folio", "Doc $i", "topic$i kinematics lecture") }
            fs.mkdirs(".trash")
            writeDoc(".trash/123__Hidden.folio", "Hidden", "invisible")

            val start = System.nanoTime()
            val stats = scanner.fullScan()
            val ms = (System.nanoTime() - start) / 1_000_000
            println("full scan of 500 documents: $ms ms")

            assertThat(stats.files).isEqualTo(500)
            assertThat(stats.folders).isEqualTo(10)
            assertThat(db.dao().documentCount()).isEqualTo(500)
            assertThat(ms).isLessThan(3_000)
            val t0 = System.nanoTime()
            assertThat(scanner.incrementalScan().indexed).isEqualTo(0)
            println("incremental scan without changes: ${(System.nanoTime() - t0) / 1_000_000} ms")
        }

    @Test
    fun incrementalScan_detectsAddModifyDeleteMove() =
        runTest {
            writeDoc("A.folio", "Alpha", "first")
            writeDoc("Sub/B.folio", "Beta", "second")
            scanner.fullScan()

            val docId = db.dao().document("A.folio")!!.docId
            writeDoc("C.folio", "Gamma", "third") // add
            writeDoc("A.folio", "Alpha renamed", "first changed, longer body", id = docId) // modify
            File(fs.root, "Sub/B.folio").delete() // delete
            fs.mkdirs("Moved")
            fs.move("A.folio", "Moved/A.folio") // move

            val stats = scanner.incrementalScan()

            assertThat(stats.removed).isEqualTo(2)
            val dao = db.dao()
            assertThat(dao.document("C.folio")!!.title).isEqualTo("Gamma")
            assertThat(dao.document("Sub/B.folio")).isNull()
            assertThat(dao.document("A.folio")).isNull()
            val moved = dao.document("Moved/A.folio")!!
            assertThat(moved.title).isEqualTo("Alpha renamed")
            assertThat(moved.docId).isEqualTo(docId)
            assertThat(moved.folderPath).isEqualTo("Moved")
            assertThat(dao.folders().first().map { it.path }).containsExactly("Moved", "Sub")
        }

    @Test
    fun search_prefixTokens_allMustMatch() =
        runTest {
            writeDoc("P.folio", "Physics Kinematics", "velocity and acceleration")
            writeDoc("M.folio", "Maths", "vectors and velocity")
            writeDoc("G.folio", "Größen", "Überblick")
            scanner.fullScan()
            val dao = db.dao()

            assertThat(dao.search(FtsQuery.of("velo")!!).map { it.path }).containsExactly("P.folio", "M.folio")
            assertThat(dao.search(FtsQuery.of("velo kinem")!!).map { it.path }).containsExactly("P.folio")
            assertThat(dao.search(FtsQuery.of("über")!!).map { it.path }).containsExactly("G.folio")
            assertThat(FtsQuery.of("  --  ")).isNull()
        }

    @Test
    fun scan_tagsFavoritesAndCorruptFiles() =
        runTest {
            writeDoc("T.folio", "Tagged", "x", tags = setOf("school", "physics"), favorite = true)
            File(fs.root, "Broken.folio").writeText("not a zip")
            scanner.fullScan()
            val dao = db.dao()

            assertThat(dao.allTags().first()).containsExactly("physics", "school")
            assertThat(dao.favorites().first().map { it.path }).containsExactly("T.folio")
            assertThat(dao.document("Broken.folio")!!.status).isEqualTo(DocStatus.CORRUPT)
            assertThat(dao.document("Broken.folio")!!.title).isEqualTo("Broken")
        }

    @Test
    fun scan_staleTempFile_deletedFreshKept() =
        runTest {
            val stale = File(fs.root, ".Doc.folio.123.tmp").apply { writeText("partial") }
            stale.setLastModified(clock.nowMs() - 11 * 60 * 1000)
            val fresh = File(fs.root, ".Other.folio.456.tmp").apply { writeText("partial") }
            fresh.setLastModified(clock.nowMs())
            scanner.incrementalScan()
            assertThat(stale.exists()).isFalse()
            assertThat(fresh.exists()).isTrue()
        }

    @Test
    fun watcher_ignoresHiddenNames() {
        assertThat(LibraryWatcher.isRelevant(".Doc.folio.1.tmp")).isFalse()
        assertThat(LibraryWatcher.isRelevant(null)).isFalse()
        assertThat(LibraryWatcher.isRelevant("Doc.folio")).isTrue()
        assertThat(LibraryWatcher.isRelevant("New folder")).isTrue()
    }

    private fun writeDoc(
        path: String,
        title: String,
        text: String,
        tags: Set<String> = emptySet(),
        favorite: Boolean = false,
        id: String = ModelFixtures.randomId(random),
    ) {
        val base =
            ModelFixtures.document(
                pages = listOf(ModelFixtures.randomPage(random, 2)),
                flows = listOf(ModelFixtures.flow(ModelFixtures.randomId(random), text)),
                id = id,
            )
        val doc: Document =
            base.copy(
                meta = base.meta.copy(title = title, favorite = favorite, tags = persistentSetOf<String>().addAll(tags)),
            )
        val result = fs.writeAtomic(path) { out -> FolioContainerWriter.write(out, DocumentEntries.all(doc, ManifestApp(), emptyMap())) }
        check(result is Outcome.Success) { result }
    }
}
