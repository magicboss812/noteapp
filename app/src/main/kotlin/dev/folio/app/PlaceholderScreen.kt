package dev.folio.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Bootstrap screen until the library and editor exist (P04/P05). */
@Composable
internal fun PlaceholderScreen(
    buildType: String,
    versionName: String,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = "Folio", style = MaterialTheme.typography.displayMedium)
            Text(text = "$buildType $versionName", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
