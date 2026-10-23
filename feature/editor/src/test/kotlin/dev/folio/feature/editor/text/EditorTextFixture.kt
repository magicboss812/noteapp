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
import dev.folio.core.text.edit.BlockEditing
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.TextSelection
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.PageText
import dev.folio.core.text.flow.toTextStyle
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.feature.editor.state.EditorSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
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

/** Fixture for the text engine of an editor session over the real storage stack (temp-dir library, in-memory index). */
@OptIn(ExperimentalCoroutinesApi::class)
abstract class EditorTextFixture {
    @get:Rule val fs = TempDirFolioFs()

    @get:Rule val main = TestDispatchersRule(UnconfinedTestDispatcher())

    protected val clock = FakeClock()
    protected val app = ManifestApp("Folio", "test")
    protected val scope by lazy { CoroutineScope(SupervisorJob() + main.testDispatcher) }
    protected val engine = TextTestEngine.create(ApplicationProvider.getApplicationContext())
    protected val body = FlowId("body")
    protected val bodyRect = TextZones.bodyRect(ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND.template)!!
    protected lateinit var db: IndexDb
    protected lateinit var documents: DocumentRepository
    protected lateinit var sessions: DocumentSessions

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

    protected fun paragraphs(
        count: Int,
        needleAt: Int = -1,
    ): String = (1..count).joinToString("\n\n") { if (it == needleAt) "Paragraph $it needle" else "Paragraph $it" }

    // The test flow has no paragraph gap, so every short paragraph is one line box of the body text.
    protected val linesPerFrame =
        TextZones.capacityUnits(bodyRect.heightPt, ModelFixtures.LINED_BACKGROUND.template.spacingPt) /
            FlowLayouter.bodyBoxUnits(ModelFixtures.FLOW_STYLE.toTextStyle())

    /** A saved note of [pages] pages whose body flow has a frame on each page in [framePages] (indices). */
    protected suspend fun note(
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

    protected fun frame(
        page: Int,
        order: Int,
    ) = FlowFrame(ObjectId("frame$page"), body, bodyRect, order, FrameRole.BODY, autoGrow = false)

    protected suspend fun open(
        path: String,
        test: TestScope,
    ): Pair<EditorSession, DocumentSession> {
        val documentSession = (sessions.open(path) as Outcome.Success).value
        return EditorSession(documentSession, 2f, main.dispatchers, test.backgroundScope, textEngine = engine) to documentSession
    }

    protected fun markdown(documentSession: DocumentSession) =
        documentSession.document.value.flows
            .getValue(body)
            .markdown

    // Points inside the first and the second line box of the body frame (short paragraphs are one line box each).
    protected val lineBoxPt =
        ModelFixtures.LINED_BACKGROUND.template.spacingPt * FlowLayouter.bodyBoxUnits(ModelFixtures.FLOW_STYLE.toTextStyle())

    protected fun firstLineY(): Float = bodyRect.top + 2f

    protected fun secondLineY(): Float = bodyRect.top + lineBoxPt + 2f

    protected suspend fun EditorText.fieldSource(): String? = field.first()?.block?.source

    protected suspend fun focusedFlow(
        path: String,
        test: TestScope,
    ): Triple<EditorSession, EditorText, PageId> {
        val (session, documentSession) = open(path, test)
        val text = checkNotNull(session.text)
        text.awaitReady()
        val page =
            documentSession.document.value.pages[0]
                .id
        return Triple(session, text, page)
    }
}
