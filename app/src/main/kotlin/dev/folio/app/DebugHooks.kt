package dev.folio.app

import android.content.Intent
import androidx.activity.ComponentActivity

/**
 * Debug automation entry points (12-performance.md#measurement, device-test skill).
 * Debug builds bind the real implementation from src/debug; release binds [NoOpDebugHooks].
 */
interface DebugHooks {
    /** Called once per activity instance after its content is set. */
    fun attach(activity: ComponentActivity)

    /** Called with the launch intent (onCreate) and every later intent (onNewIntent). */
    fun handleIntent(intent: Intent)
}

/** Release binding: does nothing. */
object NoOpDebugHooks : DebugHooks {
    override fun attach(activity: ComponentActivity) = Unit

    override fun handleIntent(intent: Intent) = Unit
}
