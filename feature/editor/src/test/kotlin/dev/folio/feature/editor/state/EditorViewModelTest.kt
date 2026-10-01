package dev.folio.feature.editor.state

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.ink.brush.BrushPresets
import dev.folio.core.ink.erase.EraserMode
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.BrushKind
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.storage.index.IndexDb
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.storage.session.DocumentSessions
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.WorkingCopyStore
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.testing.TempDirFolioFs
import dev.folio.core.testing.TestDispatchersRule
import dev.folio.feature.editor.canvas.CanvasTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** EditorViewModel over the real storage stack: temp-dir library, in-memory index, unconfined dispatchers. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EditorViewModelTest {
    @get:Rule val fs = TempDirFolioFs()

    @get:Rule val main = TestDispatchersRule(UnconfinedTestDispatcher())

    private val clock = FakeClock()
    private val app = ManifestApp("Folio", "test")
    private val store = ViewModelStore()
    private val settings = FakeSettingsStore()
    private val sessionScope by lazy { CoroutineScope(SupervisorJob() + main.testDispatcher) }
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
                sessionScope,
                app,
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    /** Runs [body], then clears the ViewModels and waits for their releases to pack before the database closes. */
    private fun editorTest(body: suspend TestScope.() -> Unit) =
        runTest(main.testDispatcher) {
            body()
            store.clear()
            awaitReleases()
        }

    /** Releases are queued behind the unconfined test body; joining lets them pack (indexing runs on Room's thread). */
    private suspend fun awaitReleases() {
        sessionScope.coroutineContext.job.children
            .forEach { it.join() }
    }

    private fun viewModel(path: String): EditorViewModel =
        ViewModelProvider.create(
            store,
            viewModelFactory {
                initializer {
                    EditorViewModel(path, DENSITY, sessions, main.dispatchers, ToolOptionsStore(settings), ToolbarDockStore(settings))
                }
            },
        )[path, EditorViewModel::class]

    /** Waits for the open, which finishes on Room's thread. */
    private suspend fun EditorViewModel.opened(): EditorSession = session.filterNotNull().first()

    private suspend fun EditorViewModel.awaitState(predicate: (EditorUiState) -> Boolean): EditorUiState = state.first(predicate)

    private suspend fun createDoc(title: String): String {
        val spec = NewDocumentSpec("", title, ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND, 2)
        return (documents.create(spec) as Outcome.Success).value.path
    }

    @Test
    fun open_existingDocument_readyWithPenAndSession() =
        editorTest {
            val vm = viewModel(createDoc("Ready"))
            val session = vm.opened()

            assertThat(vm.awaitState { it.status == EditorStatus.READY }).isEqualTo(EditorUiState(EditorStatus.READY))
            assertThat(session.document.value.pages).hasSize(2)
            assertThat(session.activeTool).isEqualTo(CanvasTool.PEN)
            assertThat(session.activeBrush.kind).isEqualTo(BrushKind.BALLPOINT)
        }

    @Test
    fun open_missingDocument_failedWithMessage() =
        editorTest {
            val vm = viewModel("missing.folio")

            assertThat(vm.state.value.status).isEqualTo(EditorStatus.FAILED)
            assertThat(vm.state.value.error).isNotEmpty()
            assertThat(vm.session.value).isNull()
        }

    @Test
    fun selectTool_builtToolsSwitchCanvasTool_unbuiltToolsIgnored() =
        editorTest {
            val vm = viewModel(createDoc("Tools"))
            val session = vm.opened()

            vm.selectTool(EditorTool.HIGHLIGHTER)
            vm.awaitState { it.tool == EditorTool.HIGHLIGHTER }
            assertThat(session.activeTool).isEqualTo(CanvasTool.PEN)
            assertThat(session.activeBrush.kind).isEqualTo(BrushKind.HIGHLIGHTER)

            vm.selectTool(EditorTool.ERASER)
            vm.awaitState { it.tool == EditorTool.ERASER }
            assertThat(session.activeTool).isEqualTo(CanvasTool.ERASER)

            vm.selectTool(EditorTool.TEXT)
            assertThat(session.tool.value).isEqualTo(EditorTool.ERASER)
            assertThat(session.activeTool).isEqualTo(CanvasTool.ERASER)
        }

    @Test
    fun sessionCommands_executeUndoRedo_throughDocumentSession() =
        editorTest {
            val session = viewModel(createDoc("Commands")).opened()
            val page =
                session.document.value.pages[0]
                    .id
            session.documentSession.loadPages(listOf(page))

            assertThat(session.execute(AddObjects(page, listOf(ModelFixtures.randomObject(Random(4)))))).isTrue()
            assertThat(
                session.document.value.pageBodies
                    .getValue(page)
                    .objects,
            ).hasSize(1)
            assertThat(session.canUndo.value).isTrue()

            assertThat(session.undo()).isTrue()
            assertThat(
                session.document.value.pageBodies
                    .getValue(page)
                    .objects,
            ).isEmpty()
            assertThat(session.redo()).isTrue()
            assertThat(
                session.document.value.pageBodies
                    .getValue(page)
                    .objects,
            ).hasSize(1)
        }

    @Test
    fun updateOptions_nextBrushUsesThem_storedAndRestoredOnReopen() =
        editorTest {
            val path = createDoc("Options")
            val vm = viewModel(path)
            val session = vm.opened()

            vm.updateOptions { it.copy(pen = it.pen.copy(kind = BrushKind.FOUNTAIN, swatches = it.pen.swatches.select(2))) }
            vm.updateOptions { it.copy(eraser = EraserOptions(EraserMode.PARTIAL)) }

            assertThat(session.activeBrush.kind).isEqualTo(BrushKind.FOUNTAIN)
            assertThat(session.activeBrush.argb).isEqualTo(BrushPresets.PEN_PALETTE[2])
            assertThat(session.eraserOptions.mode).isEqualTo(EraserMode.PARTIAL)
            assertThat(vm.awaitState { it.options.pen.kind == BrushKind.FOUNTAIN }.options).isEqualTo(session.options.value)

            store.clear()
            awaitReleases()
            val reopened = viewModel(path).opened()

            assertThat(reopened.options.value).isEqualTo(session.options.value)
            assertThat(reopened.activeBrush).isEqualTo(session.activeBrush)
        }

    @Test
    fun selectTool_selectedPenAgain_opensPenSettings_otherToolCloses() =
        editorTest {
            val vm = viewModel(createDoc("Popover"))
            vm.opened()

            vm.selectTool(EditorTool.PEN)
            assertThat(vm.awaitState { it.popover != null }.popover).isEqualTo(OptionsPopover.PenSettings)

            vm.selectTool(EditorTool.ERASER)
            assertThat(vm.awaitState { it.tool == EditorTool.ERASER }.popover).isNull()
        }

    @Test
    fun clearPage_removesInkAsOneUndoStep_highlighterOnlySparesPens() =
        editorTest {
            val session = viewModel(createDoc("Clear")).opened()
            val page =
                session.document.value.pages[0]
                    .id
            val random = Random(6)
            val pen = ModelFixtures.randomStroke(random).let { it.copy(brush = it.brush.copy(kind = BrushKind.BALLPOINT)) }
            val marker = ModelFixtures.randomStroke(random).let { it.copy(brush = it.brush.copy(kind = BrushKind.HIGHLIGHTER)) }
            session.commitStrokes(page, listOf(pen, marker))

            assertThat(session.clearPage(page, highlighterOnly = true)).isTrue()
            assertThat(
                session.document.value.pageBodies
                    .getValue(page)
                    .objects,
            ).containsExactly(pen)
            assertThat(session.clearPage(page, highlighterOnly = true)).isFalse()

            assertThat(session.clearPage(page, highlighterOnly = false)).isTrue()
            assertThat(
                session.document.value.pageBodies
                    .getValue(page)
                    .objects,
            ).isEmpty()
            assertThat(session.undo()).isTrue()
            assertThat(
                session.document.value.pageBodies
                    .getValue(page)
                    .objects,
            ).containsExactly(pen)
        }

    @Test
    fun placeToolbar_perOrientation_inStateStoredAndRestoredOnReopen() =
        editorTest {
            val path = createDoc("Dock")
            val first = viewModel(path)
            first.opened()
            val floating = ToolbarPlacement(DockMode.FLOATING, floatX = 0.25f, floatY = 0.5f, collapsed = true)

            first.placeToolbar(ScreenOrientation.PORTRAIT, ToolbarPlacement(DockMode.LEFT))
            first.placeToolbar(ScreenOrientation.LANDSCAPE, floating)

            val expected = ToolbarDocks(landscape = floating, portrait = ToolbarPlacement(DockMode.LEFT))
            assertThat(first.awaitState { it.docks == expected }.status).isEqualTo(EditorStatus.READY)
            assertThat(ToolbarDockStore.decode(settings.raw(ToolbarDockStore.KEY)!!)).isEqualTo(expected)

            store.clear()
            awaitReleases()
            assertThat(viewModel(path).awaitState { it.status == EditorStatus.READY }.docks).isEqualTo(expected)
        }

    @Test
    fun cleared_releasesSession_reopenSeesPackedEdit() =
        editorTest {
            val path = createDoc("Leave")
            val first = viewModel(path).opened()
            val page =
                first.document.value.pages[0]
                    .id
            first.documentSession.loadPages(listOf(page))
            first.execute(AddObjects(page, listOf(ModelFixtures.randomObject(Random(5)))))

            store.clear()
            awaitReleases()
            val second = viewModel(path).opened()
            second.documentSession.loadPages(listOf(page))

            assertThat(second.documentSession).isNotSameInstanceAs(first.documentSession)
            assertThat(
                second.document.value.pageBodies
                    .getValue(page)
                    .objects,
            ).hasSize(1)
        }

    private companion object {
        const val DENSITY = 2.75f
    }
}
