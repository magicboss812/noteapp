package dev.folio.feature.editor.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.canvas.CanvasHost
import dev.folio.feature.editor.state.EditorSession
import dev.folio.feature.editor.state.EditorStatus
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.EditorUiState
import dev.folio.feature.editor.state.EditorViewModel
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.ToolOptions

/** Editor destination for the document at library path [docPath]; [onBack] returns to the library. */
@Composable
fun EditorRoute(
    docPath: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    canvasListener: EditorCanvasListener? = null,
    density: Float = LocalDensity.current.density,
    viewModel: EditorViewModel =
        hiltViewModel<EditorViewModel, EditorViewModel.Factory>(key = docPath) { it.create(docPath, density) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    EditorScreen(
        state = state,
        onBack = onBack,
        onSelectTool = viewModel::selectTool,
        modifier = modifier,
        onOptionsChange = viewModel::updateOptions,
        onPopover = viewModel::showPopover,
        onClearPage = viewModel::clearCurrentPage,
    ) { canvasModifier ->
        session?.let { EditorCanvas(it, canvasListener, canvasModifier) }
    }
}

@Composable
private fun EditorCanvas(
    session: EditorSession,
    listener: EditorCanvasListener?,
    modifier: Modifier = Modifier,
) {
    CanvasHost(session, modifier, onHost = { listener?.shown(session, it) })
    DisposableEffect(session, listener) { onDispose { listener?.gone(session) } }
}

/**
 * Stateless editor screen (10-editor-ui.md#screen-structure): toolbar row 1 above the [canvas] slot,
 * which is only composed once the document is open; the tool options row and its popovers float over it.
 */
@Composable
fun EditorScreen(
    state: EditorUiState,
    onBack: () -> Unit,
    onSelectTool: (EditorTool) -> Unit,
    modifier: Modifier = Modifier,
    onOptionsChange: ((ToolOptions) -> ToolOptions) -> Unit = {},
    onPopover: (OptionsPopover?) -> Unit = {},
    onClearPage: () -> Unit = {},
    canvas: @Composable (Modifier) -> Unit,
) {
    val colors = FolioTheme.colors
    val sideInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
    Column(modifier.fillMaxSize().background(colors.background)) {
        EditorToolbarRow1(
            tool = state.tool,
            onHome = onBack,
            onSelectTool = onSelectTool,
            modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        )
        Box(Modifier.weight(1f).fillMaxWidth().background(colors.canvasSurround), contentAlignment = Alignment.Center) {
            when (state.status) {
                EditorStatus.OPENING -> {
                    Unit
                }

                EditorStatus.READY -> {
                    canvas(Modifier.fillMaxSize())
                    ToolOptionsRow(
                        tool = state.tool,
                        options = state.options,
                        onChange = onOptionsChange,
                        onPopover = onPopover,
                        modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(sideInsets),
                    )
                    OptionsPopoverLayer(state.popover, state.options, onOptionsChange, onPopover, onClearPage)
                }

                EditorStatus.FAILED -> {
                    Text(
                        text = "This note cannot be opened: ${state.error}",
                        style = FolioTheme.type.body,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(FolioTheme.space.s24),
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCanvas(modifier: Modifier = Modifier) {
    Box(modifier)
}

@Preview(name = "editor light", widthDp = 1164, heightDp = 777)
@Composable
private fun EditorLightPreview() {
    FolioTheme(darkTheme = false) {
        EditorScreen(EditorUiState(EditorStatus.READY), onBack = {}, onSelectTool = {}) { PreviewCanvas(it) }
    }
}

@Preview(name = "editor dark", widthDp = 1164, heightDp = 777, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EditorDarkPreview() {
    FolioTheme(darkTheme = true) {
        EditorScreen(EditorUiState(EditorStatus.READY), onBack = {}, onSelectTool = {}) { PreviewCanvas(it) }
    }
}
