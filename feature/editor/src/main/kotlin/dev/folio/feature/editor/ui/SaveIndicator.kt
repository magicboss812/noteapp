package dev.folio.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.session.SaveState

private val DOT_SIZE = 8.dp
private val ERROR_TARGET = 44.dp

/**
 * Subtle save-state dot (R-FILE-02): faint when everything is written, warm while edits wait or are written,
 * red with a "Not saved. Tap to retry" label after a failed save; the error state is the only tappable one.
 */
@Composable
internal fun SaveIndicator(
    state: SaveState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    val dot: Color =
        when (state) {
            SaveState.Saved -> colors.success.copy(alpha = SAVED_ALPHA)
            SaveState.Saving -> colors.warning
            SaveState.Error -> colors.danger
        }
    val description =
        when (state) {
            SaveState.Saved -> "Saved"
            SaveState.Saving -> "Saving"
            SaveState.Error -> "Not saved. Tap to retry"
        }
    val tap = if (state == SaveState.Error) Modifier.heightIn(min = ERROR_TARGET).clickable(onClick = onRetry) else Modifier
    Row(
        modifier.semantics { contentDescription = description }.then(tap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(DOT_SIZE).clip(CircleShape).background(dot))
        if (state == SaveState.Error) {
            Spacer(Modifier.width(FolioTheme.space.s8))
            Text(description, style = FolioTheme.type.caption, color = colors.danger)
        }
    }
}

private const val SAVED_ALPHA = 0.45f
