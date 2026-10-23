package dev.folio.feature.editor.text

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextZones
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.TextEdit
import dev.folio.core.model.edit.UpdateFlows
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
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.toTextStyle
import dev.folio.feature.editor.state.EditorSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The text engine of an editor session over the real storage stack (temp-dir library, in-memory index). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EditorTextTest {
    @get:Rule val fs = TempDirFolioFs()

    @get:Rule val main = TestDispatchersRule(UnconfinedTestDispatcher())

    private val clock = FakeClock()
    private val app = ManifestApp("Folio", "test")
    private val scope by lazy { CoroutineScope(SupervisorJob() + main.testDispatcher) }
    private val engine = TextTestEngine.create(ApplicationProvider.getApplicationContext())
    private val body = FlowId("body")
    private val bodyRect = TextZones.bodyRect(ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND.template)!!
    private lateinit var db: IndexDb
    private lateinit var documents: DocumentRepository
    private lateinit var sessions: DocumentSessions

    @Before
    fun setUp() {
        val appFs = fs.sub(".app")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDb::class.java).build()
        val scanner = LibraryScanner(fs, db, clock, main.dispatchers)
        documents = DocumentRepository(fs, scanner, db.dao(), clock, main.dispatchers, app)
        sessions = DocumentSessions(WorkingCopyStore(appFs, fs), Packer(fs, appFs, clock), scanner, clock, main.dispatchers, scope, app)
    }

    @After
    fun tearDown() {
        scope.cancel() // stops the sessions' pack timers before the index goes away
        db.close()
    }

    private fun paragraphs(
        count: Int,
        needleAt: Int = -1,
    ): String = (1..count).joinToString("\n\n") { if (it == needleAt) "Paragraph $it needle" else "Paragraph $it" }

    // The test flow has no paragraph gap, so every short paragraph is one line box of the body text.
    private val linesPerFrame =
        TextZones.capacityUnits(bodyRect.heightPt, ModelFixtures.LINED_BACKGROUND.template.spacingPt) /
            FlowLayouter.bodyBoxUnits(ModelFixtures.FLOW_STYLE.toTextStyle())

    /** A saved note of [pages] pages whose body flow has a frame on each page in [framePages] (indices). */
    private suspend fun note(
        pages: Int,
        framePages: List<Int>,
        markdown: String,
    ): String {
        val path =
            (
                documents.create(
                    NewDocumentSpec("", "Text", ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND, pages),
                ) as Outcome.Success
            ).value.path
        val session = (sessions.open(path) as Outcome.Success).value
        val ids =
            framePages.map {
                session.document.value.pages[it]
                    .id
            }
        session.loadPages(ids)
        val frames = framePages.mapIndexed { order, index -> AddObjects(ids[order], listOf(frame(index, order))) }
        session.execute(Batch(listOf(UpdateFlows(listOf(ModelFixtures.flow("body", markdown)))) + frames))
        sessions.close(session)
        return path
    }

    private fun frame(
        page: Int,
        order: Int,
    ) = FlowFrame(ObjectId("frame$page"), body, bodyRect, order, FrameRole.BODY, autoGrow = false)

    private suspend fun open(
        path: String,
        test: TestScope,
    ): Pair<EditorSession, DocumentSession> {
        val documentSession = (sessions.open(path) as Outcome.Success).value
        return EditorSession(documentSession, 2f, main.dispatchers, test.backgroundScope, textEngine = engine) to documentSession
    }

    @Test
    fun pageTexts_framesOnPagesBeyondTheDecodeLimit_everyFramePageShowsText() =
        runTest(main.testDispatcher) {
            // 40 pages > the session's 30 decoded pages; frames on pages 3, 25 and 39 are only found by loading them.
            val path = note(40, listOf(3, 25, 39), paragraphs(3 * linesPerFrame))
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)

            text.awaitReady()

            val shown = text.snapshot.value.pages.keys
            val expected =
                listOf(3, 25, 39).map {
                    documentSession.document.value.pages[it]
                        .id
                }
            assertThat(shown).containsAtLeastElementsIn(expected)
            assertThat(documentSession.decodedPageCount).isAtMost(33)
        }

    @Test
    fun editFlow_typingPastTheLastBodyFrame_addsContinuationPageInTheSameUndoStep() =
        runTest(main.testDispatcher) {
            val original = paragraphs(5)
            val path = note(1, listOf(0), original)
            val (session, documentSession) = open(path, this)
            checkNotNull(session.text).awaitReady()
            assertThat(documentSession.document.value.pages).hasSize(1)

            val typed = TextEdit(original.length, original.length, "\n\n" + paragraphs(3 * linesPerFrame))
            assertThat(session.editFlow(body, listOf(typed), "type")).isTrue()

            val grown = documentSession.document.value
            assertThat(grown.pages.size).isGreaterThan(1)
            assertThat(grown.flows.getValue(body).markdown).isEqualTo(original + typed.replacement)
            val continued =
                grown
                    .page(grown.pages[1].id)!!
                    .objects
                    .filterIsInstance<FlowFrame>()
                    .single()
            assertThat(continued.flow).isEqualTo(body)
            assertThat(continued.role).isEqualTo(FrameRole.BODY)

            assertThat(session.undo()).isTrue()

            val undone = documentSession.document.value
            assertThat(undone.pages).hasSize(1)
            assertThat(undone.flows.getValue(body).markdown).isEqualTo(original)
            assertThat(documentSession.canUndo.value).isFalse()
        }

    @Test
    fun showSearchMatch_matchOnALaterFramePage_scrollsToThatPage() =
        runTest(main.testDispatcher) {
            val path = note(35, listOf(2, 20, 34), paragraphs(2 * linesPerFrame + 3, needleAt = 2 * linesPerFrame + 2))
            val (session, documentSession) = open(path, this)
            val jumps = ArrayList<PageId>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { session.pageJumps.toList(jumps) }

            val page = session.showSearchMatch("needle")
            yield() // the unconfined collector resumes once this test body suspends

            val last =
                documentSession.document.value.pages[34]
                    .id
            assertThat(page).isEqualTo(last)
            assertThat(jumps).containsExactly(last)
        }

    @Test
    fun showSearchMatch_noMatch_staysPut() =
        runTest(main.testDispatcher) {
            val path = note(3, listOf(0, 1, 2), paragraphs(10))
            val (session, _) = open(path, this)
            val jumps = ArrayList<PageId>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { session.pageJumps.toList(jumps) }

            assertThat(session.showSearchMatch("zebra")).isNull()
            assertThat(jumps).isEmpty()
        }

    @Test
    fun duplicatePages_bodyFrame_copyHoldsOnlyTheTextShownOnThatPage() =
        runTest(main.testDispatcher) {
            val markdown = paragraphs(3 * linesPerFrame - 5)
            val path = note(3, listOf(0, 1, 2), markdown)
            val (session, documentSession) = open(path, this)
            checkNotNull(session.text).awaitReady()
            val second =
                documentSession.document.value.pages[1]
                    .id

            val copy = session.duplicatePages(listOf(second))

            val doc = documentSession.document.value
            assertThat(copy).isNotNull()
            val copied =
                doc
                    .page(copy!!)!!
                    .objects
                    .filterIsInstance<FlowFrame>()
                    .single()
            val shown = doc.flows.getValue(copied.flow).markdown
            assertThat(shown).isNotEmpty()
            assertThat(shown.length).isLessThan(markdown.length)
            assertThat(markdown).contains(shown)
            assertThat(doc.flows.getValue(body).markdown).isEqualTo(markdown)
        }
}
