package dev.folio.feature.library.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.feature.library.state.LibraryEntryViewModel

// Placeholder layout constants until the design system tokens exist (P04-T01).
private val CONTENT_MAX_WIDTH = 560.dp
private val GAP = 24.dp

/** Library entry: onboarding while all-files access is missing, else the library (placeholder until P05). */
@Composable
fun LibraryEntryRoute(viewModel: LibraryEntryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    LibraryEntryScreen(
        state = state,
        onGrantAccess = {
            val uri = Uri.parse("package:${context.packageName}")
            context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, uri))
        },
    )
}

/** Stateless entry screen. */
@Composable
fun LibraryEntryScreen(
    state: LibraryAccessState,
    onGrantAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when (state) {
            LibraryAccessState.Checking -> Unit
            is LibraryAccessState.NeedsPermission -> StorageOnboarding(state.folder, onGrantAccess)
            is LibraryAccessState.Ready -> LibraryPlaceholder(state.rootPath)
            is LibraryAccessState.Failed -> CenteredColumn { Text(text = "The library folder cannot be used: ${state.message}") }
        }
    }
}

@Composable
private fun CenteredColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(GAP),
        verticalArrangement = Arrangement.spacedBy(GAP, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

@Composable
private fun StorageOnboarding(
    folder: String,
    onGrantAccess: () -> Unit,
) {
    CenteredColumn {
        Text(text = "Keep your notes as files", style = MaterialTheme.typography.headlineMedium)
        Text(
            text =
                "Folio stores every notebook as a normal file in $folder, so you can copy it to a computer or back it up. " +
                    "Android needs you to allow access to all files once for this; Folio never goes online.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = CONTENT_MAX_WIDTH),
        )
        Button(onClick = onGrantAccess) { Text("Allow file access") }
    }
}

@Composable
private fun LibraryPlaceholder(rootPath: String) {
    CenteredColumn {
        Text(text = "Library", style = MaterialTheme.typography.headlineMedium)
        Text(text = rootPath, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(name = "onboarding light", widthDp = 1164, heightDp = 777)
@Composable
private fun OnboardingLightPreview() {
    MaterialTheme { LibraryEntryScreen(LibraryAccessState.NeedsPermission("Documents/Folio"), onGrantAccess = {}) }
}

@Preview(name = "onboarding dark", widthDp = 1164, heightDp = 777, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        LibraryEntryScreen(LibraryAccessState.NeedsPermission("Documents/Folio"), onGrantAccess = {})
    }
}

@Preview(name = "library light", widthDp = 1164, heightDp = 777)
@Composable
private fun LibraryLightPreview() {
    MaterialTheme { LibraryEntryScreen(LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio"), onGrantAccess = {}) }
}

@Preview(name = "library dark", widthDp = 1164, heightDp = 777, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LibraryDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        LibraryEntryScreen(LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio"), onGrantAccess = {})
    }
}
