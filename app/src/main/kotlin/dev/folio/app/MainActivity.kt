package dev.folio.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint
import dev.folio.app.nav.FolioNavHost
import dev.folio.app.nav.NavigationViewModel
import dev.folio.core.designsystem.theme.FolioTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var debugHooks: DebugHooks

    private val navigation: NavigationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val navigator = navigation.navigator
        val canvasListener = debugHooks.editorCanvas
        setContent {
            FolioTheme {
                FolioNavHost(navigator, canvasListener = canvasListener)
            }
        }
        debugHooks.attach(this, navigator)
        debugHooks.handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        debugHooks.handleIntent(intent)
    }
}
