package dev.folio.app.debug

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dagger.Lazy
import dev.folio.app.DebugHooks
import dev.folio.app.nav.AppNavigator
import dev.folio.app.nav.AppRoute
import dev.folio.app.spikes.SpikeFontsView
import dev.folio.app.spikes.spikeFontsCommand
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.storage.library.LibraryAccess
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.session.DocumentSessions
import dev.folio.feature.editor.ui.EditorCanvasListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug build [DebugHooks]: runs debug commands from intents, hosts the frame-time overlay and observes
 * the editor canvas for the canvas commands.
 */
@MainThread
@Singleton
internal class AppDebugHooks
    @Inject
    constructor(
        private val dispatchers: FolioDispatchers,
        private val libraryAccess: LibraryAccess,
        sessions: Lazy<DocumentSessions>, // lazy: storage is built on the first `open` only
        documents: Lazy<DocumentRepository>,
    ) : DebugHooks {
        private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
        private var navigator: AppNavigator? = null
        private val canvas: CanvasDebug =
            CanvasDebug(sessions::get, documents::get, scope) { path ->
                if (state.overlay != null) state.navigate(DebugAppState.LIBRARY) // the editor must not be covered
                navigator?.openEditor(path)
            }
        private val state =
            DebugAppState(
                library = ::libraryJson,
                baseScreen = { if (libraryAccess.isGranted()) DebugAppState.LIBRARY else ONBOARDING },
                editorShown = { navigator?.current is AppRoute.Editor },
                openDocPath = { canvas.openDoc },
                canvasZoom = { canvas.zoom },
                canvas = { canvas.json() },
            ).also { it.onNavigate = { route -> showRoute(route) } }
        private var activity: ComponentActivity? = null
        private var overlay: FrameTimeOverlay? = null
        private var overlayVisible = false
        private var spike: View? = null // the shown spike screen, if any

        override val editorCanvas: EditorCanvasListener get() = canvas
        private val commands =
            DebugCommands(
                state,
                extra =
                    mapOf(
                        SpikeFontsView.ROUTE to { arg -> spikeFontsCommand(spike as? SpikeFontsView, arg) },
                        "open" to canvas::open,
                        "zoom-anim" to canvas::zoomAnim,
                        "scroll-page" to canvas::scrollPage,
                        "seed-strokes" to canvas::seedStrokes,
                        "reorder-page" to canvas::reorderPage,
                        "tool" to canvas::tool,
                        "undo" to canvas::undo,
                        "redo" to canvas::redo,
                    ),
            ) { visible -> setOverlayVisible(visible) }

        override fun attach(
            activity: ComponentActivity,
            navigator: AppNavigator,
        ) {
            val created = FrameTimeOverlay(activity).also { it.install() }
            this.activity = activity
            this.navigator = navigator
            overlay = created
            spike = null
            created.setVisible(overlayVisible)
            state.overlay?.let { showRoute(it) } // a recreated activity shows the spike screen again
            activity.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        created.dispose()
                        if (overlay === created) overlay = null
                        if (this.activity === activity) {
                            this.activity = null
                            spike = null
                        }
                    }
                },
            )
        }

        // `library` pops the navigator to the library; spike screens cover the app content (D-002).
        private fun showRoute(route: String) {
            if (route == DebugAppState.LIBRARY) navigator?.toLibrary()
            val host = activity ?: return
            spike?.let { (it.parent as? ViewGroup)?.removeView(it) }
            spike = if (route == SpikeFontsView.ROUTE) SpikeFontsView(host, dispatchers) else null
            spike?.let {
                host.addContentView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                overlay?.bringToFront()
            }
        }

        override fun handleIntent(intent: Intent) {
            // Launch extras and commands are consumed so a recreated activity does not apply them again.
            intent.getStringExtra(EXTRA_DOC)?.let {
                state.requestedDoc = it
                intent.removeExtra(EXTRA_DOC)
            }
            intent.getStringExtra(EXTRA_ROUTE)?.let { route ->
                if (!state.navigate(route)) FolioLog.w(DebugReply.TAG, "unknown route '$route'")
                intent.removeExtra(EXTRA_ROUTE)
            }
            val command = intent.getStringExtra(EXTRA_CMD) ?: return
            val arg = intent.getStringExtra(EXTRA_ARG)?.takeUnless { it == NO_ARG }
            val nonce = intent.getStringExtra(EXTRA_NONCE) ?: "none"
            intent.removeExtra(EXTRA_CMD)
            FolioLog.i(DebugReply.TAG, commands.execute(command, arg).toLogLine(nonce))
        }

        private fun libraryJson(): JsonObject =
            buildJsonObject {
                put("granted", libraryAccess.isGranted())
                put("root", libraryAccess.rootPath)
                putJsonArray("folders") { libraryAccess.existingSystemFolders().forEach { add(JsonPrimitive(it)) } }
            }

        private fun setOverlayVisible(visible: Boolean) {
            overlayVisible = visible
            overlay?.setVisible(visible)
        }

        private companion object {
            const val ONBOARDING = "onboarding"
            const val EXTRA_CMD = "folio.debug.cmd"
            const val EXTRA_ARG = "folio.debug.arg"
            const val EXTRA_NONCE = "folio.debug.nonce"
            const val EXTRA_DOC = "folio.debug.doc"
            const val EXTRA_ROUTE = "folio.debug.route"
            const val NO_ARG = "_" // debugcmd.sh sends "_" when no argument is given
        }
    }
