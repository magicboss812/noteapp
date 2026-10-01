package dev.folio.feature.editor.canvas

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.Outcome
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.InkStroke
import dev.folio.core.model.PageId
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.render.viewport.toViewPx
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
import dev.folio.feature.editor.state.EditorSession
import dev.folio.feature.editor.state.EditorTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor canvas on the tablet with the real wet-ink surface, session and storage stack: stylus
 * MotionEvents (TOOL_TYPE_STYLUS) go through `Instrumentation.sendPointerSync` into the window, strokes
 * commit through the androidx.ink callbacks. The library is a temp directory of the test app, never
 * `Documents/Folio` or `Documents/Folio-Debug`.
 */
@RunWith(AndroidJUnit4::class)
class StylusEditingTest {
    @get:Rule val fs = TempDirFolioFs()

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val clock = FakeClock()
    private val app = ManifestApp("Folio", "androidTest")
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dispatchers = FolioDispatchers(Dispatchers.Main, Dispatchers.IO, Dispatchers.Default, Dispatchers.IO, Dispatchers.Default)
    private lateinit var db: IndexDb
    private lateinit var documents: DocumentRepository
    private lateinit var sessions: DocumentSessions
    private var current by mutableStateOf<EditorSession?>(null)
    private var host: CanvasHostView? = null

    @Before
    fun setUp() {
        val appFs = fs.sub(".app")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDb::class.java).build()
        val scanner = LibraryScanner(fs, db, clock, dispatchers)
        documents = DocumentRepository(fs, scanner, db.dao(), clock, dispatchers, app)
        sessions = DocumentSessions(WorkingCopyStore(appFs, fs), Packer(fs, appFs, clock), scanner, clock, dispatchers, scope, app)
        compose.setContent {
            current?.let { session ->
                CanvasHost(session, Modifier.fillMaxSize(), onHost = { host = it })
            }
        }
    }

    @After
    fun tearDown() {
        runBlocking { sessions.packAll() }
        scope.cancel()
        db.close()
    }

    private fun createDoc(): String {
        val spec = NewDocumentSpec("", "Instrumented", ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND, 1)
        return (runBlocking { documents.create(spec) } as Outcome.Success).value.path
    }

    /** Opens [path] in a fresh editor session and shows its canvas. */
    private fun enter(path: String): EditorSession {
        val opened = runBlocking { sessions.open(path) } as Outcome.Success
        val density = compose.activity.resources.displayMetrics.density
        val session = EditorSession(opened.value, density, dispatchers, scope)
        compose.runOnUiThread { current = session }
        compose.waitUntil(TIMEOUT_MS) { host?.isAttachedToWindow == true && session.viewport.viewWidthPx > 0f }
        compose.waitUntil(TIMEOUT_MS) {
            session.document.value.pageBodies
                .isNotEmpty()
        }
        return session
    }

    /** Closes the session (the final pack) after the canvas left the composition. */
    private fun leave(session: EditorSession) {
        compose.runOnUiThread { current = null }
        compose.waitForIdle()
        runBlocking { sessions.close(session.documentSession) }
    }

    private fun EditorSession.firstPage(): PageId = document.value.pages[0].id

    private fun EditorSession.strokeCount(): Int =
        document.value.pageBodies[firstPage()]
            ?.objects
            ?.count { it is InkStroke } ?: 0

    /** One stylus drag of [rowIndex] along a horizontal line of the first page, 12 points over 100 ms. */
    private fun drag(
        session: EditorSession,
        rowIndex: Int,
        toolType: Int = MotionEvent.TOOL_TYPE_STYLUS,
    ) {
        val view = checkNotNull(host)
        val onScreen = IntArray(2).also { compose.runOnUiThread { view.getLocationOnScreen(it) } }
        val page = session.firstPage()
        val yPt = FIRST_ROW_PT + rowIndex * ROW_STEP_PT
        val points =
            List(STEPS) { i ->
                val p = session.viewport.toViewPx(page, PointPt(START_X_PT + i * STEP_PT, yPt))
                (p.x + onScreen[0]) to (p.y + onScreen[1])
            }
        val downTime = SystemClock.uptimeMillis()
        points.forEachIndexed { i, (x, y) ->
            val action =
                when (i) {
                    0 -> MotionEvent.ACTION_DOWN
                    points.lastIndex -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
            val event = stylusEvent(downTime, downTime + i * STEP_MS, action, x, y, toolType)
            instrumentation.sendPointerSync(event)
            event.recycle()
            Thread.sleep(STEP_MS)
        }
    }

    private fun stylusEvent(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float,
        toolType: Int,
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0 }.also { it.toolType = toolType })
        val coords =
            arrayOf(
                MotionEvent.PointerCoords().apply {
                    this.x = x
                    this.y = y
                    pressure = 0.6f
                    size = 0.1f
                },
            )
        return MotionEvent.obtain(downTime, eventTime, action, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_STYLUS, 0)
    }

    private fun EditorSession.awaitStrokes(count: Int) = compose.waitUntil(TIMEOUT_MS) { strokeCount() == count }

    @Test
    fun draw_fiveStrokesUndoTwoRedoOne_leaveAndReopen_fourStrokesRemain() {
        val path = createDoc()
        val session = enter(path)

        repeat(DRAWN) { row ->
            drag(session, row)
            session.awaitStrokes(row + 1)
        }
        assertThat(runBlocking { session.undo() }).isTrue()
        assertThat(runBlocking { session.undo() }).isTrue()
        assertThat(session.strokeCount()).isEqualTo(DRAWN - 2)
        assertThat(runBlocking { session.redo() }).isTrue()
        assertThat(session.strokeCount()).isEqualTo(DRAWN - 1)

        leave(session)
        val reopened = enter(path)
        reopened.awaitStrokes(DRAWN - 1)
        assertThat(reopened.strokeCount()).isEqualTo(DRAWN - 1)
    }

    @Test
    fun erase_stylusDragAcrossTwoOfThreeStrokes_removesThemAndUndoBringsThemBack() {
        val session = enter(createDoc())
        repeat(3) { row ->
            drag(session, row)
            session.awaitStrokes(row + 1)
        }

        assertThat(session.selectTool(EditorTool.ERASER)).isTrue()
        // The pen's rows 0 and 1 lie under a vertical eraser sweep; row 2 stays.
        eraseSweep(session, rows = 0..1)
        session.awaitStrokes(1)
        assertThat(session.canUndo.value).isTrue()

        assertThat(runBlocking { session.undo() }).isTrue()
        assertThat(session.strokeCount()).isEqualTo(3)
    }

    /** Eraser-tip drag down through the middle of the given rows. */
    private fun eraseSweep(
        session: EditorSession,
        rows: IntRange,
    ) {
        val view = checkNotNull(host)
        val onScreen = IntArray(2).also { compose.runOnUiThread { view.getLocationOnScreen(it) } }
        val page = session.firstPage()
        val x = START_X_PT + (STEPS / 2) * STEP_PT
        val top = FIRST_ROW_PT + rows.first * ROW_STEP_PT - ROW_STEP_PT / 2
        val bottom = FIRST_ROW_PT + rows.last * ROW_STEP_PT + ROW_STEP_PT / 4
        val downTime = SystemClock.uptimeMillis()
        for (i in 0..SWEEP_STEPS) {
            val yPt = top + (bottom - top) * i / SWEEP_STEPS
            val p = session.viewport.toViewPx(page, PointPt(x, yPt))
            val action =
                when (i) {
                    0 -> MotionEvent.ACTION_DOWN
                    SWEEP_STEPS -> MotionEvent.ACTION_UP
                    else -> MotionEvent.ACTION_MOVE
                }
            val event =
                stylusEvent(downTime, downTime + i * STEP_MS, action, p.x + onScreen[0], p.y + onScreen[1], MotionEvent.TOOL_TYPE_ERASER)
            instrumentation.sendPointerSync(event)
            event.recycle()
            Thread.sleep(STEP_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val DRAWN = 5
        const val STEPS = 12
        const val STEP_MS = 8L
        const val STEP_PT = 12f
        const val START_X_PT = 100f
        const val FIRST_ROW_PT = 120f
        const val ROW_STEP_PT = 60f
        const val SWEEP_STEPS = 20
    }
}
