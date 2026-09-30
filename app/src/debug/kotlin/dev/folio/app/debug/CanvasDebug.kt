package dev.folio.app.debug

import android.content.Context
import android.view.View
import androidx.annotation.MainThread
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.ink.brush.BrushCatalog
import dev.folio.core.ink.brush.BrushPresets
import dev.folio.core.model.Background
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.InkStroke
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.TemplateKind
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.RemoveObjects
import dev.folio.core.render.template.TemplatePresets
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.storage.session.DocumentSession
import dev.folio.core.storage.session.DocumentSessions
import dev.folio.feature.editor.canvas.CanvasController
import dev.folio.feature.editor.canvas.CanvasHost
import dev.folio.feature.editor.canvas.CanvasHostView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Parsed `open` argument: a library path, or `<template>:N` such as `blank:20` or `lined:5` (a generated
 * N-page A4 document with that default template; any kind but CUSTOM).
 */
internal sealed interface OpenTarget {
    data class Path(
        val path: String,
    ) : OpenTarget

    data class Generated(
        val pages: Int,
        val kind: TemplateKind = TemplateKind.BLANK,
    ) : OpenTarget {
        val title: String get() = "${kind.name.lowercase()}-$pages"
        val path: String get() = "$FOLDER/$title.folio"
    }

    companion object {
        /** Library folder of generated documents. */
        const val FOLDER = "perf"
        private const val MAX_PAGES = 500

        fun parse(arg: String?): OpenTarget? {
            if (arg.isNullOrBlank()) return null
            val prefix = arg.substringBefore(':', missingDelimiterValue = "")
            val kind = TemplateKind.entries.firstOrNull { it != TemplateKind.CUSTOM && it.name.equals(prefix, ignoreCase = true) }
            if (kind == null) return Path(arg)
            val pages = arg.substringAfter(':').toIntOrNull()?.takeIf { it in 1..MAX_PAGES } ?: return null
            return Generated(pages, kind)
        }
    }
}

/** Parsed `zoom-anim from,to,durationMs` (zooms are multiples of fit-width). */
internal data class ZoomAnim(
    val from: Float,
    val to: Float,
    val durationMs: Long,
) {
    companion object {
        private const val PARTS = 3
        private const val MAX_DURATION_MS = 10_000L

        fun parse(arg: String?): ZoomAnim? {
            val parts = arg?.split(',')?.takeIf { it.size == PARTS } ?: return null
            val zooms = Viewport.MIN_ZOOM..Viewport.MAX_ZOOM
            val from = parts[0].toFloatOrNull()?.takeIf { it in zooms }
            val to = parts[1].toFloatOrNull()?.takeIf { it in zooms }
            val durationMs = parts[2].toLongOrNull()?.takeIf { it in 0..MAX_DURATION_MS }
            return if (from != null && to != null && durationMs != null) ZoomAnim(from, to, durationMs) else null
        }
    }
}

/** Parsed `scroll-page n[,durationMs]`; [page] is 1-based. */
internal data class ScrollPage(
    val page: Int,
    val durationMs: Long,
) {
    companion object {
        private const val MAX_DURATION_MS = 60_000L

        fun parse(arg: String?): ScrollPage? {
            val parts = arg?.split(',')?.takeIf { it.size in 1..2 } ?: return null
            val page = parts[0].toIntOrNull()?.takeIf { it >= 1 } ?: return null
            val durationMs = if (parts.size == 2) parts[1].toLongOrNull()?.takeIf { it in 0..MAX_DURATION_MS } else 0L
            return durationMs?.let { ScrollPage(page, it) }
        }
    }
}

/** Until EditorSession exists (P04) a document session plus a viewport stands in for the controller. */
private class SessionCanvasController(
    val session: DocumentSession,
    density: Float,
    override val renderDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val scope: CoroutineScope,
) : CanvasController {
    override val document: StateFlow<Document> get() = session.document
    override val viewport = Viewport(density)

    // Middle ballpoint preset in ink black until the toolbar exists (P04).
    override val activeBrush =
        BrushSpec(
            BrushKind.BALLPOINT,
            BrushPresets.PEN_PALETTE[0],
            BrushPresets.widthsPt(BrushKind.BALLPOINT)[1],
            BrushCatalog.DEFAULT.latestVersion,
        )

    override fun loadPages(ids: Collection<PageId>) {
        scope.launch {
            val result = session.loadPages(ids)
            if (result is Outcome.Failure) FolioLog.w(DebugReply.TAG, "loadPages: ${result.message}")
        }
    }

    override suspend fun commitStrokes(
        pageId: PageId,
        strokes: List<InkStroke>,
    ): Boolean {
        // Off the main thread: the session lock, the command and the autosave scheduling (ink:commit budget).
        val result = withContext(ioDispatcher) { session.execute(AddObjects(pageId, strokes)) }
        if (result is Outcome.Failure) FolioLog.w(DebugReply.TAG, "commitStrokes: ${result.message}")
        return result is Outcome.Success
    }
}

/**
 * Debug commands for the canvas host (P03-T02, T04): `open <path>|blank:N` opens a document through
 * [DocumentSessions] and shows it on the `canvas` route; `zoom-anim` and `scroll-page` script the
 * viewport for frame stats; `seed-strokes n[,page]` replaces a page's objects with n synthetic strokes.
 * Replaced by the P04 editor route and EditorSession.
 */
@MainThread
internal class CanvasDebug(
    private val sessionsProvider: () -> DocumentSessions,
    private val documentsProvider: () -> DocumentRepository,
    private val scope: CoroutineScope,
    private val density: Float,
    private val renderDispatcher: CoroutineDispatcher,
    private val ioDispatcher: CoroutineDispatcher,
    private val onOpened: () -> Unit,
) {
    private val sessions: DocumentSessions get() = sessionsProvider()
    private val documents: DocumentRepository get() = documentsProvider()
    private var controller: SessionCanvasController? = null
    private var host: CanvasHostView? = null
    private var opening: String? = null
    private var lastError: String? = null

    /** Library path of the open document. */
    val openDoc: String? get() = controller?.session?.path

    /** Zoom of the laid-out canvas. */
    val zoom: Float? get() = controller?.viewport?.takeIf { it.layout != null }?.zoom

    fun open(arg: String?): DebugReply {
        val target =
            OpenTarget.parse(arg) ?: return DebugReply.error("open needs <library path> or <template>:<1..500> (blank, lined, ...)")
        val path =
            when (target) {
                is OpenTarget.Path -> target.path
                is OpenTarget.Generated -> target.path
            }
        opening = path
        lastError = null
        scope.launch {
            val result = sessions.open(path).orCreate(target)
            opening = null
            when (result) {
                is Outcome.Success -> show(result.value)
                is Outcome.Failure -> lastError = "${result.message}: ${result.cause}"
            }
            FolioLog.i(DebugReply.TAG, "open $path -> ${lastError ?: "ok"}")
        }
        return DebugReply.ok(buildJsonObject { put("opening", path) })
    }

    fun zoomAnim(arg: String?): DebugReply {
        val anim =
            ZoomAnim.parse(arg) ?: return DebugReply.error("zoom-anim needs from,to,ms (zoom ${Viewport.MIN_ZOOM}..${Viewport.MAX_ZOOM})")
        val view = host ?: return DebugReply.error(NO_CANVAS)
        view.animateZoom(anim.from, anim.to, anim.durationMs)
        return DebugReply.ok(json() ?: JsonObject(emptyMap()))
    }

    fun scrollPage(arg: String?): DebugReply {
        val scroll = ScrollPage.parse(arg) ?: return DebugReply.error("scroll-page needs n[,ms] with n >= 1")
        val view = host ?: return DebugReply.error(NO_CANVAS)
        val count = controller?.viewport?.layout?.size ?: 0
        if (scroll.page > count) return DebugReply.error("page ${scroll.page} > $count pages")
        view.scrollToPage(scroll.page - 1, scroll.durationMs)
        return DebugReply.ok(json() ?: JsonObject(emptyMap()))
    }

    fun seedStrokes(arg: String?): DebugReply {
        val seed = SeedStrokes.parse(arg) ?: return DebugReply.error("seed-strokes needs n[,page] with n in 1..${SeedStrokes.MAX_COUNT}")
        val current = controller ?: return DebugReply.error(NO_CANVAS)
        val ref =
            current.document.value.pages
                .getOrNull(seed.page - 1) ?: return DebugReply.error("no page ${seed.page}")
        val strokes = SyntheticStrokes.generate(seed.count, ref.spec.widthPt, ref.spec.heightPt)
        scope.launch {
            val loaded = current.session.loadPages(listOf(ref.id))
            val existing =
                current.document.value.pageBodies[ref.id]
                    ?.objects
                    ?.map { it.id }
                    .orEmpty()
            val result =
                if (loaded is Outcome.Failure) {
                    loaded
                } else {
                    current.session.execute(Batch(listOf(RemoveObjects(ref.id, existing), AddObjects(ref.id, strokes))))
                }
            FolioLog.i(
                DebugReply.TAG,
                "seed-strokes ${seed.count} on page ${seed.page} -> ${(result as? Outcome.Failure)?.message ?: "ok"}",
            )
        }
        return DebugReply.ok(buildJsonObject { put("seeding", seed.count) })
    }

    /** The canvas screen for the open document, or null if none is open. */
    fun createView(context: Context): View? {
        val current = controller ?: return null
        return ComposeView(context).apply {
            setContent { CanvasHost(current, Modifier.fillMaxSize(), onHost = { host = it }) }
        }
    }

    /** The canvas screen was removed. */
    fun onViewGone() {
        host = null
    }

    /** Canvas facts for `state`; null when nothing is open or opening. */
    fun json(): JsonObject? {
        val current = controller
        if (current == null && opening == null && lastError == null) return null
        return buildJsonObject {
            opening?.let { put("opening", it) }
            lastError?.let { put("error", it) }
            if (current != null) {
                val vp = current.viewport
                put("pages", current.document.value.pages.size)
                put("zoom", vp.zoom)
                put("offsetYPx", vp.offsetYPx)
                put("visibleView", host != null)
                put("animating", host?.isAnimating)
                put("requestedHz", host?.requestedHz)
                put("activeHz", host?.display?.refreshRate)
                put(
                    "objects",
                    current.document.value.pageBodies.values
                        .sumOf { it.objects.size },
                )
                host?.inkStats?.let { ink ->
                    put(
                        "ink",
                        buildJsonObject {
                            put("started", ink.started)
                            put("finished", ink.finished)
                            put("canceled", ink.canceled)
                        },
                    )
                }
                host?.handoffStats?.let { h ->
                    put(
                        "handoff",
                        buildJsonObject {
                            put("committed", h.committed)
                            put("pending", h.pending)
                            put("removed", h.removed)
                        },
                    )
                }
                host?.stylusCapabilities?.let { caps ->
                    put("stylus", "pressure=${caps.pressure} tilt=${caps.tilt} orientation=${caps.orientation} hover=${caps.hover}")
                }
                host?.tileStats?.let { t ->
                    put(
                        "tiles",
                        buildJsonObject {
                            put("budgetMiBPerLayer", t.budgetBytesPerLayer / MIB)
                            put("background", t.backgroundTiles)
                            put("backgroundMiB", t.backgroundBytes / MIB)
                            put("content", t.contentTiles)
                            put("contentMiB", t.contentBytes / MIB)
                            put("pending", t.pending)
                            put("evictions", t.evictions)
                        },
                    )
                }
            }
        }
    }

    private suspend fun Outcome<DocumentSession>.orCreate(target: OpenTarget): Outcome<DocumentSession> {
        if (this is Outcome.Success || target !is OpenTarget.Generated) return this
        val background = Background(WHITE, TemplatePresets.default(target.kind), null)
        val spec = NewDocumentSpec(OpenTarget.FOLDER, target.title, A4, background, target.pages)
        return documents.create(spec).flatMap { sessions.open(it.path) }
    }

    private fun show(session: DocumentSession) {
        val previous = controller
        if (previous?.session === session) {
            onOpened()
            return
        }
        previous?.let { old -> scope.launch { sessions.close(old.session) } }
        controller = SessionCanvasController(session, density, renderDispatcher, ioDispatcher, scope)
        onOpened()
    }

    private companion object {
        const val NO_CANVAS = "no canvas shown; run open first"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val MIB = 1024L * 1024L
        val A4 = PageSpec.Fixed(PaperSize.A4, Orientation.PORTRAIT)
    }
}
