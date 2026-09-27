package dev.folio.app.debug

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.folio.app.DebugHooks
import dev.folio.core.common.FolioLog
import javax.inject.Inject
import javax.inject.Singleton

/** Debug build [DebugHooks]: runs debug commands from intents and hosts the frame-time overlay. */
@Singleton
internal class AppDebugHooks
    @Inject
    constructor() : DebugHooks {
        private val state = DebugAppState()
        private var overlay: FrameTimeOverlay? = null
        private var overlayVisible = false
        private val commands = DebugCommands(state) { visible -> setOverlayVisible(visible) }

        override fun attach(activity: ComponentActivity) {
            val created = FrameTimeOverlay(activity).also { it.install() }
            overlay = created
            created.setVisible(overlayVisible)
            activity.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY && overlay === created) overlay = null
                },
            )
        }

        override fun handleIntent(intent: Intent) {
            intent.getStringExtra(EXTRA_DOC)?.let { state.requestedDoc = it }
            intent.getStringExtra(EXTRA_ROUTE)?.let { route ->
                if (!state.navigate(route)) FolioLog.w(DebugReply.TAG, "unknown route '$route'")
            }
            val command = intent.getStringExtra(EXTRA_CMD) ?: return
            val arg = intent.getStringExtra(EXTRA_ARG)?.takeUnless { it == NO_ARG }
            val nonce = intent.getStringExtra(EXTRA_NONCE) ?: "none"
            // Consume the command so a recreated activity does not run it again.
            intent.removeExtra(EXTRA_CMD)
            FolioLog.i(DebugReply.TAG, commands.execute(command, arg).toLogLine(nonce))
        }

        private fun setOverlayVisible(visible: Boolean) {
            overlayVisible = visible
            overlay?.setVisible(visible)
        }

        private companion object {
            const val EXTRA_CMD = "folio.debug.cmd"
            const val EXTRA_ARG = "folio.debug.arg"
            const val EXTRA_NONCE = "folio.debug.nonce"
            const val EXTRA_DOC = "folio.debug.doc"
            const val EXTRA_ROUTE = "folio.debug.route"
            const val NO_ARG = "_" // debugcmd.sh sends "_" when no argument is given
        }
    }
