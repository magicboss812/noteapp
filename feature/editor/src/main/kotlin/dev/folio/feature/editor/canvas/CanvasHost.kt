package dev.folio.feature.editor.canvas

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Embeds a [CanvasHostView] for [controller]. The document is collected into the view directly (no
 * recomposition per document change); [onHost] receives the view once created (debug automation).
 */
@Composable
fun CanvasHost(
    controller: CanvasController,
    modifier: Modifier = Modifier,
    onHost: (CanvasHostView) -> Unit = {},
) = CanvasHost(controller, modifier, onHost) { CanvasHostView(it, controller) }

/** [CanvasHost] with a custom view factory (tests swap the wet-ink surface). */
@Composable
internal fun CanvasHost(
    controller: CanvasController,
    modifier: Modifier = Modifier,
    onHost: (CanvasHostView) -> Unit = {},
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
        LaunchedEffect(view) {
            if (view == null) return@LaunchedEffect
            controller.document.collect { view.setDocument(it) }
        }
        LaunchedEffect(view) {
            if (view == null) return@LaunchedEffect
            controller.viewCommands.collect { view.perform(it) }
        }
    }
}
