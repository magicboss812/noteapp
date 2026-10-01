package dev.folio.feature.editor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.PageContent
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.RenderTarget
import dev.folio.feature.editor.state.EditorSession
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** A page picture: the page panel, the overview and the template previews of the settings sheet draw one per page. */
internal typealias PageThumbnail = @Composable (page: PageRef, modifier: Modifier) -> Unit

// Thumbnails render at one width and scale down in the UI.
private const val THUMB_WIDTH_PX = 240

/** Paper-colored frame of [page] without content: the placeholder while a picture renders, and the previews/tests. */
@Composable
internal fun PaperThumbnail(
    page: PageRef,
    modifier: Modifier = Modifier,
) {
    Box(modifier.aspectRatio(page.spec.widthPt / page.spec.heightPt).background(Color(page.background.paperArgb)))
}

/** [PageThumbnail] of [session]'s pages, rendered by the page renderer (paper, template, ink) off the main thread. */
@Composable
internal fun SessionPageThumbnail(
    session: EditorSession,
    page: PageRef,
    modifier: Modifier = Modifier,
) {
    val inDocument = remember(session, page.id) { session.document.value.indexOfPage(page.id) >= 0 }
    val body: Page? by
        remember(session, page.id) { session.document.map { it.pageBodies[page.id] }.distinctUntilChanged() }
            .collectAsStateWithLifecycle(session.bodyOf(page.id))
    // A page that is not decoded yet loads through the session (previews of settings are not in the document).
    if (inDocument) LaunchedEffect(page.id, body == null) { if (body == null) session.loadPages(listOf(page.id)) }
    val picture by produceState<ImageBitmap?>(null, page, body) {
        value = withContext(session.renderDispatcher) { renderThumbnail(page, body) }
    }
    val shown = picture
    if (shown == null) {
        // Paper color until the picture is ready, so a page never flashes empty.
        PaperThumbnail(page, modifier)
    } else {
        Image(shown, null, modifier.aspectRatio(page.spec.widthPt / page.spec.heightPt), contentScale = ContentScale.FillBounds)
    }
}

// The body now, as the first value of the flow collected above (a plain function: nothing to observe here).
private fun EditorSession.bodyOf(id: PageId): Page? = document.value.pageBodies[id]

private fun renderThumbnail(
    page: PageRef,
    body: Page?,
): ImageBitmap {
    val widthPt = page.spec.widthPt
    val scale = THUMB_WIDTH_PX / widthPt
    val heightPx = (page.spec.heightPt * scale).toInt().coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(THUMB_WIDTH_PX, heightPx, Bitmap.Config.ARGB_8888)
    val content = body?.takeIf { it.id == page.id }?.let(PageContent::of)
    // One renderer per call: it is not thread-safe and the render dispatcher runs two threads.
    PageRenderer().draw(
        Canvas(bitmap),
        page,
        content,
        RectPt.ofSize(0f, 0f, widthPt, page.spec.heightPt),
        scale,
        RenderTarget.EXPORT_RASTER,
    )
    return bitmap.asImageBitmap()
}
