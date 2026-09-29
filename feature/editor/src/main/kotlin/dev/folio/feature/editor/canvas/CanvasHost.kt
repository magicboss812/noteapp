package dev.folio.feature.editor.canvas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Embeds a [CanvasHostView] for [controller]. The page list is collected into the view directly (no
 * recomposition per document change); [onHost] receives the view once created (debug automation).
 */
@Composable
fun CanvasHost(
    controller: CanvasController,
    modifier: Modifier = Modifier,
    onHost: (CanvasHostView) -> Unit = {},
) {
    key(controller) {
        var host by remember { mutableStateOf<CanvasHostView?>(null) }
        AndroidView(
            factory = { context ->
                CanvasHostView(context, controller).also {
                    host = it
                    onHost(it)
                }
            },
            modifier = modifier,
        )
        val view = host
        LaunchedEffect(view) {
            if (view == null) return@LaunchedEffect
            controller.document
                .map { it.pages }
                .distinctUntilChanged()
                .collect { view.setPages(it) }
        }
    }
}
