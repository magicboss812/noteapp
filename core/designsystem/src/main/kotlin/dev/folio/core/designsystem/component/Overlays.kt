package dev.folio.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.designsystem.theme.folioShadow

private val SHEET_HANDLE_WIDTH = 36.dp
private val SHEET_HANDLE_HEIGHT = 4.dp
private val DIALOG_MIN_WIDTH = 280.dp
private val SNACKBAR_MAX_WIDTH = 560.dp
private val SNACKBAR_MIN_HEIGHT = 48.dp

/**
 * Popover card (pen settings, width editor, color picker, tool panels): `surface`, radius 18, 16 dp padding, popover
 * shadow, centered title with a close chip when [onClose] is set (11-design-system.md#components).
 */
@Composable
fun FolioPopover(
    title: String,
    modifier: Modifier = Modifier,
    width: Dp = FolioTheme.space.popoverWidth,
    onClose: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.popover
    Column(
        modifier =
            modifier
                .width(width)
                .folioShadow(FolioTheme.elevation.popover, shape, colors.shadow)
                .background(colors.surface, shape)
                .padding(start = space.s16, end = space.s16, bottom = space.s16),
        verticalArrangement = Arrangement.spacedBy(space.s12),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = space.touchTarget), contentAlignment = Alignment.Center) {
            Text(
                text = title,
                style = FolioTheme.type.titleSmall,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = space.touchTarget),
            )
            if (onClose != null) CloseChip(onClick = onClose, modifier = Modifier.align(Alignment.CenterEnd))
        }
        content()
    }
}

/** Modal bottom sheet with the Folio surface, sheet radius, scrim and a headline title. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolioSheet(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = FolioTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        shape = FolioTheme.shapes.sheet,
        containerColor = colors.surface,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
        scrimColor = colors.scrim,
        dragHandle = { SheetHandle() },
    ) {
        FolioSheetContent(title = title, content = content)
    }
}

/** Inner layout of [FolioSheet]: title then content, with sheet padding. */
@Composable
fun FolioSheetContent(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val space = FolioTheme.space
    Column(
        modifier = modifier.fillMaxWidth().padding(start = space.s24, end = space.s24, bottom = space.s24),
        verticalArrangement = Arrangement.spacedBy(space.s16),
    ) {
        Text(text = title, style = FolioTheme.type.headline, color = FolioTheme.colors.textPrimary)
        content()
    }
}

@Composable
internal fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = FolioTheme.space.s12), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(SHEET_HANDLE_WIDTH, SHEET_HANDLE_HEIGHT)
                .background(FolioTheme.colors.divider, FolioTheme.shapes.full),
        )
    }
}

/** Confirmation dialog. [destructive] paints the confirm label in the danger color (no fill, D-023). */
@Composable
fun FolioDialog(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissLabel: String? = "Cancel",
    destructive: Boolean = false,
) {
    Dialog(onDismissRequest = onDismissRequest) {
        FolioDialogContent(
            title = title,
            confirmLabel = confirmLabel,
            onConfirm = onConfirm,
            onDismiss = onDismissRequest,
            modifier = modifier,
            text = text,
            dismissLabel = dismissLabel,
            destructive = destructive,
        )
    }
}

/** Card of [FolioDialog] without the window, for inline use and screenshots. */
@Composable
fun FolioDialogContent(
    title: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    text: String? = null,
    dismissLabel: String? = "Cancel",
    destructive: Boolean = false,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.card
    Column(
        modifier =
            modifier
                .widthIn(min = DIALOG_MIN_WIDTH, max = space.dialogWidth)
                .folioShadow(FolioTheme.elevation.popover, shape, colors.shadow)
                .background(colors.surfaceDialog, shape)
                .padding(start = space.s24, end = space.s12, top = space.s24, bottom = space.s8),
        verticalArrangement = Arrangement.spacedBy(space.s12),
    ) {
        Text(
            text = title,
            style = FolioTheme.type.title,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(end = space.s12),
        )
        if (text != null) {
            Text(text = text, style = FolioTheme.type.body, color = colors.textSecondary, modifier = Modifier.padding(end = space.s12))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(space.s4, Alignment.End),
        ) {
            if (dismissLabel != null) {
                FolioButton(label = dismissLabel, onClick = onDismiss, style = FolioButtonStyle.Text, textColor = colors.textPrimary)
            }
            FolioButton(label = confirmLabel, onClick = onConfirm, style = FolioButtonStyle.Text, destructive = destructive)
        }
    }
}

/** `surface` stadium message with a border and an optional accent action such as "Undo". */
@Composable
fun FolioSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.stadium
    Row(
        modifier =
            modifier
                .widthIn(max = SNACKBAR_MAX_WIDTH)
                .heightIn(min = SNACKBAR_MIN_HEIGHT)
                .folioShadow(FolioTheme.elevation.popover, shape, colors.shadow)
                .background(colors.surface, shape)
                .border(space.borderWidth, colors.border, shape)
                .padding(start = space.s20, end = space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = FolioTheme.type.label,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f, fill = false).padding(vertical = space.s12),
        )
        if (actionLabel != null) {
            Spacer(Modifier.width(space.s8))
            Box(
                modifier =
                    Modifier
                        .heightIn(min = space.touchTarget)
                        .clip(FolioTheme.shapes.stadium)
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = space.s12),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = actionLabel, style = FolioTheme.type.bodyMedium, color = colors.accent)
            }
        } else {
            Spacer(Modifier.width(space.s16))
        }
    }
}

/** Material snackbar host that renders [FolioSnackbar]s. */
@Composable
fun FolioSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        FolioSnackbar(
            message = data.visuals.message,
            actionLabel = data.visuals.actionLabel,
            onAction = data::performAction,
        )
    }
}
