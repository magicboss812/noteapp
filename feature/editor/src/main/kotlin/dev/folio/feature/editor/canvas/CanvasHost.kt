package dev.folio.feature.editor.canvas

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.withContext

/**
 * Embeds a [CanvasHostView] for [controller]. The document is collected into the view directly (no
 * recomposition per document change); [onHost] receives the view once created (debug automation).
 * Collectors run on [CanvasController.mainDispatcher]: the session publishes from worker threads, and the
 * effect dispatcher does not guarantee the main thread (Compose test rules resume on the emitter). [onKey] runs the
 * editor shortcut for a Ctrl or Alt chord typed in the live text field (key code, ctrl, shift, alt), which keeps the
 * key focus while a block is edited.
 */
@Composable
fun CanvasHost(
    controller: CanvasController,
    modifier: Modifier = Modifier,
    onHost: (CanvasHostView) -> Unit = {},
    onKey: (keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean) -> Boolean = { _, _, _, _ -> false },
) = CanvasHost(controller, modifier, onHost, onKey) { CanvasHostView(it, controller) }

/** [CanvasHost] with a custom view factory (tests swap the wet-ink surface). */
@Composable
internal fun CanvasHost(
    controller: CanvasController,
    modifier: Modifier = Modifier,
    onHost: (CanvasHostView) -> Unit = {},
    onKey: (keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean) -> Boolean = { _, _, _, _ -> false },
    createView: (Context) -> CanvasHostView,
) {
    key(controller) {
        var host by remember { mutableStateOf<CanvasHostView?>(null) }
        AndroidView(
            factory = { context ->
                createView(context).also {
                    host = it
                    onHost(it)
                }
            },
            modifier = modifier,
        )
        val view = host
        SideEffect { view?.onShortcut = onKey }
        LaunchedEffect(view) {
            if (view == null) return@LaunchedEffect
            withContext(controller.mainDispatcher) { controller.document.collect { view.setDocument(it) } }
        }
        LaunchedEffect(view) {
            val texts = controller.textController
            if (view == null || texts == null) return@LaunchedEffect
            withContext(controller.mainDispatcher) { texts.field.collect { view.setTextField(it) } }
        }
        LaunchedEffect(view) {
            if (view == null) return@LaunchedEffect
            withContext(controller.mainDispatcher) { controller.pageTexts.collect { view.setPageTexts(it) } }
        }
        LaunchedEffect(view) {
            if (view == null) return@LaunchedEffect
            withContext(controller.mainDispatcher) { controller.viewCommands.collect { view.perform(it) } }
        }
        LaunchedEffect(view) {
            if (view == null) return@LaunchedEffect
            withContext(controller.mainDispatcher) { controller.pageJumps.collect { view.goToPage(it) } }
        }
    }
}
