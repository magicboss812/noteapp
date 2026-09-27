package dev.folio.app.debug

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.annotation.MainThread
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.folio.app.DebugHooks
import dev.folio.app.spikes.SpikeInkView
import dev.folio.app.spikes.SpikeTilesView
import dev.folio.app.spikes.spikeInkCommand
import dev.folio.app.spikes.spikeTilesCommand
import dev.folio.app.spikes.zoomAnimCommand
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import javax.inject.Inject
import javax.inject.Singleton

/** Debug build [DebugHooks]: runs debug commands from intents and hosts the frame-time overlay. */
@MainThread
@Singleton
internal class AppDebugHooks
    @Inject
    constructor(
        private val dispatchers: FolioDispatchers,
    ) : DebugHooks {
        private val state = DebugAppState().also { it.onNavigate = ::showRoute }
        private var activity: ComponentActivity? = null
        private var overlay: FrameTimeOverlay? = null
        private var overlayVisible = false
        private var spikeInk: SpikeInkView? = null
        private var spikeTiles: SpikeTilesView? = null
        private val commands =
            DebugCommands(
                state,
                extra =
                    mapOf(
                        SpikeInkView.ROUTE to { arg -> spikeInkCommand(spikeInk, arg) },
                        SpikeTilesView.ROUTE to { arg -> spikeTilesCommand(spikeTiles, arg) },
                        "zoom-anim" to { arg -> zoomAnimCommand(spikeTiles, arg) },
                    ),
            ) { visible -> setOverlayVisible(visible) }

        override fun attach(activity: ComponentActivity) {
            val created = FrameTimeOverlay(activity).also { it.install() }
            this.activity = activity
            overlay = created
            spikeInk = null
            spikeTiles = null
            created.setVisible(overlayVisible)
            showRoute(state.route) // a recreated activity shows the current route again
            activity.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY) {
                        created.dispose()
                        if (overlay === created) overlay = null
                        if (this.activity === activity) {
                            this.activity = null
                            spikeInk = null
                            spikeTiles = null
                        }
                    }
                },
            )
        }

        // Spike screens cover the placeholder content until P04 navigation exists.
        private fun showRoute(route: String) {
            val host = activity ?: return
            if (route != SpikeInkView.ROUTE) spikeInk = spikeInk?.let(::remove)
            if (route != SpikeTilesView.ROUTE) spikeTiles = spikeTiles?.let(::remove)
            when (route) {
                SpikeInkView.ROUTE -> if (spikeInk == null) spikeInk = SpikeInkView(host).also { show(host, it) }
                SpikeTilesView.ROUTE -> if (spikeTiles == null) spikeTiles = SpikeTilesView(host, dispatchers).also { show(host, it) }
            }
        }

        private fun show(
            host: ComponentActivity,
            view: View,
        ) {
            host.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            overlay?.bringToFront()
        }

        /** Detaches [view] and returns null so callers can clear their reference in one expression. */
        private fun <T : View> remove(view: T): T? {
            (view.parent as? ViewGroup)?.removeView(view)
            return null
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
