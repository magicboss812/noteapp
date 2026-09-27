package dev.folio.app

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

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

@Preview(name = "light", widthDp = 1164, heightDp = 777)
@Composable
private fun PlaceholderScreenLightPreview() {
    MaterialTheme { PlaceholderScreen(buildType = "debug", versionName = "0.1.0") }
}

@Preview(name = "dark", widthDp = 1164, heightDp = 777, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PlaceholderScreenDarkPreview() {
    MaterialTheme(colorScheme = darkColorScheme()) { PlaceholderScreen(buildType = "debug", versionName = "0.1.0") }
}
