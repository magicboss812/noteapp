package dev.folio.app.debug

import androidx.annotation.MainThread
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.model.Background
import dev.folio.core.model.Orientation
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
import dev.folio.feature.editor.canvas.CanvasHostView
import dev.folio.feature.editor.canvas.CanvasTool
import dev.folio.feature.editor.state.EditorSession
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.ui.EditorCanvasListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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

/**
 * Debug commands for the editor canvas (P03-T02..T08, P04-T02): `open <path>|<template>:N` makes sure
 * the document exists (generating it if needed), then [onOpen] navigates to its editor; `zoom-anim` and
 * `scroll-page` script the viewport for frame stats; `seed-strokes n[,page]` replaces a page's objects
 * with n synthetic strokes; `tool` switches pen and eraser, `undo` and `redo` step the history. All act on
 * the editor canvas on screen, which this class observes as the editor's [EditorCanvasListener].
 */
@MainThread
internal class CanvasDebug(
    private val sessionsProvider: () -> DocumentSessions,
    private val documentsProvider: () -> DocumentRepository,
    private val scope: CoroutineScope,
    private val onOpen: (String) -> Unit,
) : EditorCanvasListener {
    private val sessions: DocumentSessions get() = sessionsProvider()
    private val documents: DocumentRepository get() = documentsProvider()
    private var session: EditorSession? = null
    private var host: CanvasHostView? = null
    private var opening: String? = null
    private var lastError: String? = null

    /** Library path of the document on screen. */
    val openDoc: String? get() = session?.documentSession?.path

    /** Zoom of the laid-out canvas. */
    val zoom: Float? get() = session?.viewport?.takeIf { it.layout != null }?.zoom

    override fun shown(
        session: EditorSession,
        host: CanvasHostView,
    ) {
        this.session = session
        this.host = host
    }

    override fun gone(session: EditorSession) {
        if (this.session !== session) return
        this.session = null
        host = null
    }

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
            // The editor's ViewModel opens the same path and gets this (cached) session.
            val result = sessions.open(path).orCreate(target)
            opening = null
            when (result) {
                is Outcome.Success -> onOpen(result.value.path)
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
        val count = session?.viewport?.layout?.size ?: 0
        if (scroll.page > count) return DebugReply.error("page ${scroll.page} > $count pages")
        view.scrollToPage(scroll.page - 1, scroll.durationMs)
        return DebugReply.ok(json() ?: JsonObject(emptyMap()))
    }

    fun seedStrokes(arg: String?): DebugReply {
        val seed = SeedStrokes.parse(arg) ?: return DebugReply.error("seed-strokes needs n[,page] with n in 1..${SeedStrokes.MAX_COUNT}")
        val current = session?.documentSession ?: return DebugReply.error(NO_CANVAS)
        val ref =
            current.document.value.pages
                .getOrNull(seed.page - 1) ?: return DebugReply.error("no page ${seed.page}")
        val strokes = SyntheticStrokes.generate(seed.count, ref.spec.widthPt, ref.spec.heightPt)
        scope.launch {
            val loaded = current.loadPages(listOf(ref.id))
            val existing =
                current.document.value.pageBodies[ref.id]
                    ?.objects
                    ?.map { it.id }
                    .orEmpty()
            val result =
                if (loaded is Outcome.Failure) {
                    loaded
                } else {
                    current.execute(Batch(listOf(RemoveObjects(ref.id, existing), AddObjects(ref.id, strokes))))
                }
            FolioLog.i(
                DebugReply.TAG,
                "seed-strokes ${seed.count} on page ${seed.page} -> ${(result as? Outcome.Failure)?.message ?: "ok"}",
            )
        }
        return DebugReply.ok(buildJsonObject { put("seeding", seed.count) })
    }

    fun tool(arg: String?): DebugReply {
        val setting =
            ToolSetting.parse(arg) ?: return DebugReply.error("tool needs pen or eraser[,stroke|partial][,radiusPt][,hl]")
        val current = session ?: return DebugReply.error(NO_CANVAS)
        current.selectTool(if (setting.tool == CanvasTool.ERASER) EditorTool.ERASER else EditorTool.PEN)
        // `tool pen` keeps them; like the options row, the change is stored.
        if (setting.tool == CanvasTool.ERASER) current.updateOptions { it.copy(eraser = setting.eraser) }
        return DebugReply.ok(json() ?: JsonObject(emptyMap()))
    }

    fun undo(arg: String?): DebugReply = history("undo", arg) { it.undo() }

    fun redo(arg: String?): DebugReply = history("redo", arg) { it.redo() }

    private fun history(
        name: String,
        arg: String?,
        step: suspend (EditorSession) -> Boolean,
    ): DebugReply {
        if (!arg.isNullOrBlank()) return DebugReply.error("$name takes no argument")
        val current = session ?: return DebugReply.error(NO_CANVAS)
        scope.launch {
            val ok = step(current)
            FolioLog.i(DebugReply.TAG, "$name -> ${if (ok) "ok" else "failed"}")
        }
        return DebugReply.ok(buildJsonObject { put(name, true) })
    }

    /** Canvas facts for `state`; null when nothing is shown, opening or failed. */
    fun json(): JsonObject? {
        val current = session
        if (current == null && opening == null && lastError == null) return null
        return buildJsonObject {
            opening?.let { put("opening", it) }
            lastError?.let { put("error", it) }
            if (current != null) putCanvas(current)
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putCanvas(current: EditorSession) {
        val vp = current.viewport
        val doc = current.document.value
        put("pages", doc.pages.size)
        put("zoom", vp.zoom)
        put("offsetYPx", vp.offsetYPx)
        put("visibleView", host != null)
        put("animating", host?.isAnimating)
        put("requestedHz", host?.requestedHz)
        put("activeHz", host?.display?.refreshRate)
        put("objects", doc.pageBodies.values.sumOf { it.objects.size })
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
        put("tool", "${current.tool.value.name.lowercase()} ${current.activeTool.name.lowercase()} ${current.eraserOptions}")
        put("canUndo", current.canUndo.value)
        put("canRedo", current.canRedo.value)
        host?.eraseStats?.let { e ->
            put(
                "erase",
                buildJsonObject {
                    put("gestures", e.gestures)
                    put("committed", e.committed)
                    put("strokes", e.strokes)
                    put("discarded", e.discarded)
                },
            )
        }
        host?.let { h ->
            val caps = h.stylusCapabilities
            put(
                "stylus",
                "pressure=${caps.pressure} tilt=${caps.tilt} orientation=${caps.orientation} hover=${caps.hover} " +
                    "button=${caps.primaryButton} hoverRing=${h.isHoverRingShown} hoverEvents=${h.hoverEventCount}",
            )
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

    private suspend fun Outcome<DocumentSession>.orCreate(target: OpenTarget): Outcome<DocumentSession> {
        if (this is Outcome.Success || target !is OpenTarget.Generated) return this
        val background = Background(WHITE, TemplatePresets.default(target.kind), null)
        val spec = NewDocumentSpec(OpenTarget.FOLDER, target.title, A4, background, target.pages)
        return documents.create(spec).flatMap { sessions.open(it.path) }
    }

    private companion object {
        const val NO_CANVAS = "no editor canvas shown; run open first"
        const val WHITE = 0xFFFFFFFF.toInt()
        const val MIB = 1024L * 1024L
        val A4 = PageSpec.Fixed(PaperSize.A4, Orientation.PORTRAIT)
    }
}
