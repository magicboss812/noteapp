package dev.folio.feature.editor.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioPopover
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.state.ShortcutGroup

private val HELP_WIDTH = 560.dp
private val HELP_KEYS_WIDTH = 200.dp
private val HELP_MAX_HEIGHT = 560.dp

/** Help sheet of the hardware keyboard shortcuts (Ctrl+/); a tap outside or Esc closes it. */
@Composable
internal fun ShortcutHelp(
    groups: List<ShortcutGroup>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    Box(
        modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        FolioPopover(
            title = "Keyboard shortcuts",
            // Taps inside the card must not close it.
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { } },
            width = HELP_WIDTH,
            onClose = onClose,
        ) {
            Box(Modifier.heightIn(max = HELP_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                Column(verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s8)) {
                    groups.forEach { group ->
                        Row(horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s16)) {
                            Text(
                                text = group.keys,
                                style = FolioTheme.type.bodyMedium,
                                color = colors.textPrimary,
                                modifier = Modifier.width(HELP_KEYS_WIDTH),
                            )
                            Text(text = group.description, style = FolioTheme.type.body, color = colors.textSecondary)
                        }
                    }
                }
            }
        }
    }
}
