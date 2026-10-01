package dev.folio.feature.editor.state

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.container.FolioContainerReader
import dev.folio.core.format.container.FolioEntries
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.storage.index.IndexDb
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.storage.session.DocumentSession
import dev.folio.core.storage.session.DocumentSessions
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.WorkingCopyStore
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.testing.TempDirFolioFs
import dev.folio.core.testing.TestDispatchersRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** ThumbnailGenerator over the real storage stack with a fake encoder (the renderer needs a device-like canvas). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ThumbnailGeneratorTest {
    @get:Rule val fs = TempDirFolioFs()

    @get:Rule val main = TestDispatchersRule(StandardTestDispatcher())

    private val clock = FakeClock()
    private val app = ManifestApp("Folio", "test")
    private val random = Random(77)
    private val encoded = ArrayList<Pair<PageId, Int>>()
    private lateinit var db: IndexDb
    private lateinit var documents: DocumentRepository
    private lateinit var sessions: DocumentSessions

    @Before
    fun setUp() {
        val appFs = fs.sub(".app")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDb::class.java).build()
        val scanner = LibraryScanner(fs, db, clock, main.dispatchers)
        documents = DocumentRepository(fs, scanner, db.dao(), clock, main.dispatchers, app)
        sessions =
            DocumentSessions(
                WorkingCopyStore(appFs, fs),
                Packer(fs, appFs, clock),
                scanner,
                clock,
                main.dispatchers,
                TestScope(main.testDispatcher),
                app,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun open(scope: TestScope): Pair<DocumentSession, String> {
        val spec = NewDocumentSpec("", "Thumbs", ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND, 2)
        val path = (documents.create(spec) as Outcome.Success).value.path
        val session = (sessions.open(path) as Outcome.Success).value
        ThumbnailGenerator(session, main.dispatchers, scope.backgroundScope) { page, _, px ->
            encoded += page.id to px
            byteArrayOf(px.toByte())
        }.start()
        scope.runCurrent()
        return session to path
    }

    private suspend fun DocumentSession.draw(index: Int): PageId {
        val id = document.value.pages[index].id
        loadPages(listOf(id))
        execute(AddObjects(id, listOf(ModelFixtures.randomStroke(random, ObjectId("s$index")))))
        return id
    }

    @Test
    fun noEdits_noThumbnails() =
        runTest(main.testDispatcher) {
            open(this)

            advanceTimeBy(5_000)

            assertThat(encoded).isEmpty()
        }

    @Test
    fun editOnSecondPage_afterIdle_rendersThatPageOnly() =
        runTest(main.testDispatcher) {
            val (session, path) = open(this)
            val edited = session.draw(1)

            advanceTimeBy(1_900)
            assertThat(encoded).isEmpty()
            advanceTimeBy(300)
            runCurrent()

            assertThat(encoded).containsExactly(edited to ThumbnailGenerator.THUMB_PX)
            assertThat(sessions.close(session)).isInstanceOf(Outcome.Success::class.java)
            FolioContainerReader.open(fs.root.resolve(path)).let { (it as Outcome.Success).value }.use { reader ->
                assertThat(reader.names()).contains(FolioEntries.pageThumb(edited))
                assertThat(reader.names()).doesNotContain(FolioEntries.COVER)
            }
        }

    @Test
    fun editOnFirstPage_alsoMakesTheCover() =
        runTest(main.testDispatcher) {
            val (session, _) = open(this)
            val first = session.draw(0)

            advanceTimeBy(2_500)
            runCurrent()

            assertThat(encoded)
                .containsExactly(first to ThumbnailGenerator.THUMB_PX, first to ThumbnailGenerator.COVER_PX)
        }

    @Test
    fun burstOfEdits_waitsForIdleThenRendersOnce() =
        runTest(main.testDispatcher) {
            val (session, _) = open(this)
            val page = session.draw(1)
            advanceTimeBy(1_500)
            session.execute(AddObjects(page, listOf(ModelFixtures.randomStroke(random, ObjectId("again")))))
            advanceTimeBy(1_500)
            assertThat(encoded).isEmpty()

            advanceTimeBy(1_000)
            runCurrent()

            assertThat(encoded).containsExactly(page to ThumbnailGenerator.THUMB_PX)
        }
}
