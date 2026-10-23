package dev.folio.feature.editor.state

import android.view.KeyEvent
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
import dev.folio.core.model.edit.UpdateFlows
import dev.folio.core.storage.index.IndexDb
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.storage.repo.NoteLinks
import dev.folio.core.storage.session.DocumentSessions
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.WorkingCopyStore
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.testing.TempDirFolioFs
import dev.folio.core.testing.TestDispatchersRule
import dev.folio.core.text.flow.TapTarget
import dev.folio.feature.editor.canvas.CanvasCommand
import dev.folio.feature.editor.canvas.CanvasTool
import dev.folio.feature.editor.text.TextTestEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
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
                    EditorViewModel(
                        path,
                        DENSITY,
                        null,
                        sessions,
                        main.dispatchers,
                        ToolOptionsStore(settings),
                        ToolbarDockStore(settings),
                        TextTestEngine.create(ApplicationProvider.getApplicationContext()),
                        NoteLinks(db.dao(), main.dispatchers),
                    )
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

            val ready = vm.awaitState { it.status == EditorStatus.READY }
            assertThat(ready.copy(pages = PagesUi())).isEqualTo(EditorUiState(EditorStatus.READY))
            assertThat(ready.pages.pages).hasSize(2)
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
    fun keys_ctrlZ_undoesAndCtrlShiftZ_redoes_stateFollows() =
        editorTest {
            val vm = viewModel(createDoc("Keys"))
            val session = vm.opened()
            val page =
                session.document.value.pages[0]
                    .id
            session.documentSession.loadPages(listOf(page))
            session.execute(AddObjects(page, listOf(ModelFixtures.randomObject(Random(5)))))
            vm.awaitState { it.canUndo }
            val objects = {
                session.document.value.pageBodies
                    .getValue(page)
                    .objects
            }

            assertThat(vm.onKey(KeyEvent.KEYCODE_Z, ctrl = true, shift = false, alt = false)).isTrue()
            vm.awaitState { it.canRedo && !it.canUndo }
            assertThat(objects()).isEmpty()

            assertThat(vm.onKey(KeyEvent.KEYCODE_Y, ctrl = true, shift = false, alt = false)).isTrue()
            vm.awaitState { it.canUndo && !it.canRedo }
            assertThat(objects()).hasSize(1)
        }

    @Test
    fun keys_altDigitSelectsTool_unboundKeyIsNotConsumed() =
        editorTest {
            val vm = viewModel(createDoc("Alt"))
            vm.opened()

            assertThat(vm.onKey(KeyEvent.KEYCODE_3, ctrl = false, shift = false, alt = true)).isTrue()
            assertThat(vm.awaitState { it.tool == EditorTool.ERASER }.tool).isEqualTo(EditorTool.ERASER)
            assertThat(vm.onKey(KeyEvent.KEYCODE_A, ctrl = false, shift = false, alt = false)).isFalse()
        }

    @Test
    fun keys_escapeClosesHelpThenPopover_thenIsNotConsumed() =
        editorTest {
            val vm = viewModel(createDoc("Esc"))
            vm.opened()
            vm.showPopover(OptionsPopover.ToolSettings(EditorTool.PEN))
            assertThat(vm.onKey(KeyEvent.KEYCODE_SLASH, ctrl = true, shift = false, alt = false)).isTrue()
            assertThat(vm.awaitState { it.showHelp }.popover).isEqualTo(OptionsPopover.ToolSettings(EditorTool.PEN))

            assertThat(vm.onKey(KeyEvent.KEYCODE_ESCAPE, ctrl = false, shift = false, alt = false)).isTrue()
            assertThat(vm.awaitState { !it.showHelp }.popover).isEqualTo(OptionsPopover.ToolSettings(EditorTool.PEN))
            assertThat(vm.onKey(KeyEvent.KEYCODE_ESCAPE, ctrl = false, shift = false, alt = false)).isTrue()
            assertThat(vm.awaitState { it.popover == null }.showHelp).isFalse()
            assertThat(vm.onKey(KeyEvent.KEYCODE_ESCAPE, ctrl = false, shift = false, alt = false)).isFalse()
        }

    @Test
    fun keys_zoomAndPageKeys_reachTheCanvasAsCommands() =
        editorTest {
            val vm = viewModel(createDoc("Canvas"))
            val session = vm.opened()
            // Subscribed before the first key: commands sent while no host collects are dropped.
            val received = async(start = CoroutineStart.UNDISPATCHED) { session.viewCommands.take(3).toList() }

            vm.onKey(KeyEvent.KEYCODE_EQUALS, ctrl = true, shift = false, alt = false)
            vm.onKey(KeyEvent.KEYCODE_PAGE_DOWN, ctrl = false, shift = false, alt = false)
            vm.onKey(KeyEvent.KEYCODE_MOVE_END, ctrl = true, shift = false, alt = false)

            assertThat(received.await())
                .containsExactly(CanvasCommand.ZOOM_IN, CanvasCommand.NEXT_PAGE, CanvasCommand.LAST_PAGE)
                .inOrder()
        }

    @Test
    fun keys_beforeTheDocumentIsOpen_areNotConsumed() =
        editorTest {
            val vm = viewModel("missing.folio")

            assertThat(vm.onKey(KeyEvent.KEYCODE_Z, ctrl = true, shift = false, alt = false)).isFalse()
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
    fun selectTool_selectedToolAgain_togglesItsPanel_otherToolCloses() =
        editorTest {
            val vm = viewModel(createDoc("Popover"))
            vm.opened()

            vm.selectTool(EditorTool.PEN)
            assertThat(vm.awaitState { it.popover != null }.popover).isEqualTo(OptionsPopover.ToolSettings(EditorTool.PEN))
            vm.selectTool(EditorTool.PEN)
            assertThat(vm.awaitState { it.popover == null }.tool).isEqualTo(EditorTool.PEN)

            vm.selectTool(EditorTool.ERASER)
            assertThat(vm.awaitState { it.tool == EditorTool.ERASER }.popover).isNull()
            vm.selectTool(EditorTool.ERASER)
            assertThat(vm.awaitState { it.popover != null }.popover).isEqualTo(OptionsPopover.ToolSettings(EditorTool.ERASER))

            vm.selectTool(EditorTool.HIGHLIGHTER)
            assertThat(vm.awaitState { it.tool == EditorTool.HIGHLIGHTER }.popover).isNull()
        }

    @Test
    fun panelChange_storedForItsTool_andReadByTheNextEditor() =
        editorTest {
            val doc = createDoc("Panel")
            val vm = viewModel(doc)
            vm.opened()
            vm.selectTool(EditorTool.ERASER)
            vm.selectTool(EditorTool.ERASER)
            vm.awaitState { it.popover == OptionsPopover.ToolSettings(EditorTool.ERASER) }

            // What the eraser panel's slider and switch send.
            vm.updateOptions { it.copy(eraser = it.eraser.copy(radiusPt = 17f, highlighterOnly = true)) }

            val expected = ToolOptions().eraser.copy(radiusPt = 17f, highlighterOnly = true)
            assertThat(vm.awaitState { it.options.eraser == expected }.status).isEqualTo(EditorStatus.READY)
            store.clear()
            awaitReleases()
            assertThat(viewModel(doc).awaitState { it.status == EditorStatus.READY }.options.eraser).isEqualTo(expected)
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
    fun setOptionsHidden_inStateAndStored_closesPopover() =
        editorTest {
            val vm = viewModel(createDoc("Chevron"))
            vm.opened()
            vm.showPopover(OptionsPopover.ToolSettings(EditorTool.PEN))

            vm.setOptionsHidden(true)

            val hidden = vm.awaitState { it.docks.optionsHidden }
            assertThat(hidden.popover).isNull()
            assertThat(ToolbarDockStore.decode(settings.raw(ToolbarDockStore.KEY)!!).optionsHidden).isTrue()
        }

    @Test
    fun setReadMode_stylusNavigatesAndOverlaysClose_leavingRestoresTheTool() =
        editorTest {
            val vm = viewModel(createDoc("Read"))
            val session = vm.opened()
            vm.selectTool(EditorTool.ERASER)
            vm.showPages(PageSurface.PANEL)
            vm.showPopover(OptionsPopover.ClearPage)

            vm.setReadMode(true)

            val reading = vm.awaitState { it.readMode }
            assertThat(reading.popover).isNull()
            assertThat(reading.pages.surface).isEqualTo(PageSurface.NONE)
            assertThat(session.activeTool).isEqualTo(CanvasTool.NAVIGATE)

            vm.setReadMode(false)

            assertThat(vm.awaitState { !it.readMode }.tool).isEqualTo(EditorTool.ERASER)
            assertThat(session.activeTool).isEqualTo(CanvasTool.ERASER)
        }

    @Test
    fun viewportSettled_infoPillShowsPageAndZoom() =
        editorTest {
            val vm = viewModel(createDoc("Info"))
            val session = vm.opened()
            session.viewport.setPages(session.document.value.pages, VIEW_W, VIEW_H)
            session.viewport.zoomBy(2f, VIEW_W / 2f, 0f)

            session.viewportSettled()

            assertThat(vm.awaitState { it.view.zoomPercent != 100 }.view).isEqualTo(ViewInfo(pageIndex = 0, zoomPercent = 200))
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

    private fun EditorSession.order() = document.value.pages.map { it.id }

    @Test
    fun pages_addDuplicateDeleteMove_eachIsOneUndoStep() =
        editorTest {
            val session = viewModel(createDoc("Pages")).opened()
            val (first, second) = session.order()

            val added = session.addPage(first, dev.folio.core.model.edit.PageOps.Side.AFTER)
            assertThat(session.order()).containsExactly(first, added, second).inOrder()
            assertThat(session.undo()).isTrue()
            assertThat(session.order()).containsExactly(first, second).inOrder()

            val copy = session.duplicatePages(listOf(second))
            assertThat(session.order()).containsExactly(first, second, copy).inOrder()
            assertThat(session.movePage(copy!!, 0)).isTrue()
            assertThat(session.order()).containsExactly(copy, first, second).inOrder()
            assertThat(session.undo()).isTrue()
            assertThat(session.order()).containsExactly(first, second, copy).inOrder()

            assertThat(session.deletePages(listOf(first, second))).isTrue()
            assertThat(session.order()).containsExactly(copy)
            assertThat(session.deletePages(listOf(copy))).isFalse()
            assertThat(session.undo()).isTrue()
            assertThat(session.order()).containsExactly(first, second, copy).inOrder()
        }

    @Test
    fun pages_applySettings_allPagesThenNewPages() =
        editorTest {
            val session = viewModel(createDoc("Settings")).opened()
            val ref = session.document.value.pages[0]
            val dark = PageSettings(ref).copy(paperArgb = PaperColor.DARK.argb, size = dev.folio.core.model.PaperSize.A5)

            assertThat(session.applyPageSettings(dark, ref.id, ApplyTo.ALL_PAGES)).isTrue()
            session.document.value.pages.forEach {
                assertThat(it.background.paperArgb).isEqualTo(PaperColor.DARK.argb)
                assertThat(
                    it.spec,
                ).isEqualTo(
                    dev.folio.core.model.PageSpec
                        .Fixed(dev.folio.core.model.PaperSize.A5, dev.folio.core.model.Orientation.PORTRAIT),
                )
            }
            assertThat(session.undo()).isTrue()
            assertThat(
                session.document.value.pages
                    .map { it.background.paperArgb }
                    .toSet(),
            ).containsExactly(0xFFFFFFFF.toInt())

            assertThat(session.applyPageSettings(dark, ref.id, ApplyTo.NEW_PAGES)).isTrue()
            assertThat(session.document.value.meta.defaultBackground.paperArgb).isEqualTo(PaperColor.DARK.argb)
            assertThat(session.applyPageSettings(dark, ref.id, ApplyTo.NEW_PAGES)).isFalse()
        }

    @Test
    fun pages_surfacesAndSettings_escapeClosesSettingsThenSurface() =
        editorTest {
            val vm = viewModel(createDoc("Surfaces"))
            val session = vm.opened()
            val first = session.order()[0]

            vm.showPages(PageSurface.PANEL)
            vm.showPageSettings(first)
            assertThat(vm.awaitState { it.pages.settingsPage == first }.pages.surface).isEqualTo(PageSurface.PANEL)
            assertThat(vm.state.value.pages.pages).hasSize(2)

            assertThat(vm.onKey(KeyEvent.KEYCODE_ESCAPE, ctrl = false, shift = false, alt = false)).isTrue()
            assertThat(vm.awaitState { it.pages.settingsPage == null }.pages.surface).isEqualTo(PageSurface.PANEL)
            assertThat(vm.onKey(KeyEvent.KEYCODE_ESCAPE, ctrl = false, shift = false, alt = false)).isTrue()
            assertThat(vm.awaitState { it.pages.surface == PageSurface.NONE }.pages.surface).isEqualTo(PageSurface.NONE)
        }

    @Test
    fun pages_goToPage_closesTheOverviewAndHighlightsThePage() =
        editorTest {
            val vm = viewModel(createDoc("Go"))
            val session = vm.opened()
            val second = session.order()[1]

            vm.showPages(PageSurface.OVERVIEW)
            vm.awaitState { it.pages.surface == PageSurface.OVERVIEW }
            vm.goToPage(second)

            val pages = vm.awaitState { it.pages.surface == PageSurface.NONE }.pages
            assertThat(pages.current).isEqualTo(second)
        }

    @Test
    fun linkedFrom_listsNotesLinkingHere_openBacklinkSendsOpenNote() =
        editorTest {
            val target = createDoc("Target")
            val source = createDoc("Source")
            writeText(source, "See [Target](folio://doc/${docId(target)})")
            val vm = viewModel(target)
            vm.opened()

            vm.showLinkedFrom(true)
            val linked = checkNotNull(vm.awaitState { it.linkedFrom != null }.linkedFrom)
            val event = async(start = CoroutineStart.UNDISPATCHED) { vm.events.first() }
            vm.openBacklink(linked.single())

            assertThat(linked.map { it.title }).containsExactly("Source")
            assertThat(event.await()).isEqualTo(EditorEvent.OpenNote(source))
            assertThat(vm.awaitState { it.linkedFrom == null }.linkedFrom).isNull()
        }

    @Test
    fun linkEvents_urlNoteByIdOrTitle_missingNotesAndSelfLinks() =
        editorTest {
            val optics = createDoc("Optics")
            val self = createDoc("Self")
            val opticsId = docId(optics)
            val selfId = docId(self)
            val links = EditorLinks(NoteLinks(db.dao(), main.dispatchers), { selfId }, self)

            assertThat(links.eventFor(TapTarget.Url("https://example.org"))).isEqualTo(EditorEvent.OpenUrl("https://example.org"))
            assertThat(links.eventFor(TapTarget.Note(opticsId, "Old title"))).isEqualTo(EditorEvent.OpenNote(optics))
            assertThat(links.eventFor(TapTarget.Note(null, " optics"))).isEqualTo(EditorEvent.OpenNote(optics))
            assertThat(links.eventFor(TapTarget.Note("gone", "Gone"))).isEqualTo(EditorEvent.NoteNotFound)
            assertThat(links.eventFor(TapTarget.Note(null, "Opt"))).isEqualTo(EditorEvent.NoteNotFound)
            assertThat(links.eventFor(TapTarget.Note(selfId, "Self"))).isNull()
            assertThat(links.eventFor(TapTarget.Checkbox(0))).isNull()
            assertThat(links.suggestions("").map { it.path }).containsExactly(optics)
        }

    private suspend fun docId(path: String): String {
        val documentSession = (sessions.open(path) as Outcome.Success).value
        val id = documentSession.document.value.meta.id.value
        sessions.close(documentSession)
        return id
    }

    private suspend fun writeText(
        path: String,
        markdown: String,
    ) {
        val documentSession = (sessions.open(path) as Outcome.Success).value
        documentSession.execute(UpdateFlows(listOf(ModelFixtures.flow("body", markdown))))
        sessions.close(documentSession)
    }

    private companion object {
        const val DENSITY = 2.75f

        // Pad 7 landscape window, px.
        const val VIEW_W = 3200f
        const val VIEW_H = 2136f
    }
}
