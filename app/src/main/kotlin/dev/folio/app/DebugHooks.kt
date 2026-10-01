package dev.folio.app

import android.content.Intent
import androidx.activity.ComponentActivity
import dev.folio.app.nav.AppNavigator
import dev.folio.feature.editor.ui.EditorCanvasListener

/**
 * Debug automation entry points (12-performance.md#measurement, device-test skill).
 * Debug builds bind the real implementation from src/debug; release binds [NoOpDebugHooks].
 */
interface DebugHooks {
    /** Observes editor canvases; null when not needed. */
    val editorCanvas: EditorCanvasListener?

    /** Called once per activity instance after its content is set; [navigator] drives the app's screens. */
    fun attach(
        activity: ComponentActivity,
        navigator: AppNavigator,
    )

    /** Called with the launch intent (onCreate) and every later intent (onNewIntent). */
    fun handleIntent(intent: Intent)
}

/** Release binding: does nothing. */
object NoOpDebugHooks : DebugHooks {
    override val editorCanvas: EditorCanvasListener? = null

    override fun attach(
        activity: ComponentActivity,
        navigator: AppNavigator,
    ) = Unit

    override fun handleIntent(intent: Intent) = Unit
}
