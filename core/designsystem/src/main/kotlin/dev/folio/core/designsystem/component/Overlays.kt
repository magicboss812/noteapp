package dev.folio.core.designsystem.component

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.designsystem.theme.folioShadow

private val SHEET_HANDLE_WIDTH = 36.dp
private val SHEET_HANDLE_HEIGHT = 4.dp
private val DIALOG_MIN_WIDTH = 280.dp
private val DIALOG_MAX_WIDTH = 440.dp
private val SNACKBAR_MAX_WIDTH = 560.dp
private val SNACKBAR_MIN_HEIGHT = 48.dp

/** Modal bottom sheet with the Folio surface, sheet radius, scrim and a serif title. */
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
        Text(text = title, style = FolioTheme.type.displaySmall, color = FolioTheme.colors.textPrimary)
        content()
    }
}

@Composable
internal fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = FolioTheme.space.s12), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(SHEET_HANDLE_WIDTH, SHEET_HANDLE_HEIGHT)
                .background(FolioTheme.colors.border, FolioTheme.shapes.full),
        )
    }
}

/** Confirmation dialog. [destructive] paints the confirm button in the danger color. */
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
    val shape = FolioTheme.shapes.xl
    Column(
        modifier =
            modifier
                .widthIn(min = DIALOG_MIN_WIDTH, max = DIALOG_MAX_WIDTH)
                .folioShadow(FolioTheme.elevation.floating, shape, colors.shadow)
                .background(colors.surface, shape)
                .padding(space.s24),
        verticalArrangement = Arrangement.spacedBy(space.s12),
    ) {
        Text(text = title, style = FolioTheme.type.titleMedium, color = colors.textPrimary)
        if (text != null) Text(text = text, style = FolioTheme.type.body, color = colors.textSecondary)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = space.s8),
            horizontalArrangement = Arrangement.spacedBy(space.s8, Alignment.End),
        ) {
            if (dismissLabel != null) DialogButton(label = dismissLabel, onClick = onDismiss, filled = false)
            DialogButton(
                label = confirmLabel,
                onClick = onConfirm,
                filled = true,
                fill = if (destructive) colors.danger else colors.accent,
            )
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    onClick: () -> Unit,
    filled: Boolean,
    fill: Color = Color.Unspecified,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Box(
        modifier =
            Modifier
                .heightIn(min = space.touchTarget)
                .clip(FolioTheme.shapes.full)
                .background(if (filled) fill else Color.Transparent)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = space.s20),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = FolioTheme.type.label, color = if (filled) colors.onAccent else colors.textSecondary)
    }
}

/** Inverted pill message with an optional action such as "Undo". */
@Composable
fun FolioSnackbar(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.m
    Row(
        modifier =
            modifier
                .widthIn(max = SNACKBAR_MAX_WIDTH)
                .heightIn(min = SNACKBAR_MIN_HEIGHT)
                .folioShadow(FolioTheme.elevation.floating, shape, colors.shadow)
                .background(colors.textPrimary, shape)
                .padding(start = space.s16, end = space.s4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = FolioTheme.type.body,
            color = colors.surface,
            modifier = Modifier.weight(1f, fill = false).padding(vertical = space.s12),
        )
        if (actionLabel != null) {
            Spacer(Modifier.width(space.s8))
            Box(
                modifier =
                    Modifier
                        .heightIn(min = space.touchTarget)
                        .clip(FolioTheme.shapes.s)
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = space.s12),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = actionLabel, style = FolioTheme.type.label, color = colors.accentInverse)
            }
        } else {
            Spacer(Modifier.width(space.s12))
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
