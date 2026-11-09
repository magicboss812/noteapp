package dev.folio.feature.editor.canvas

import androidx.annotation.MainThread
import dev.folio.core.common.FolioLog
import dev.folio.core.common.PerfMonitor
import dev.folio.core.ink.erase.EraseResult
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Page
import dev.folio.core.model.PageId

/** Dry handoff counters for debug `state`, since the canvas view was created. */
data class HandoffStats(
    /** Strokes handed to the session. */
    val committed: Int,
    /** Wet strokes waiting for their tiles. */
    val pending: Int,
    /** Wet strokes removed after the frame showing their committed copy. */
    val removed: Int,
)

/**
 * Moves finished wet strokes into the document (05-canvas-rendering.md#dry-handoff): each batch of
 * [WetStroke]s becomes [InkStroke]s committed per page through [commit] (one undo step per page).
 * The wet copies stay until [check] finds the committed stroke [isDrawn] by the content tiles; then
 * the layers are [invalidate]d and the wet strokes removed in the same run loop: androidx.ink hides a
 * removed stroke with the next HWUI frame, which is the one drawing the tiles, so no frame lacks the
 * stroke or draws it twice (A-046; removing one frame later flickered). A rejected commit drops the wet stroke.
 * An erase gesture sees pending strokes ([withPending]); while its preview replaces one ([erasing]), the wet
 * copy waits for the replacing fragments instead (none: it leaves at once).
 */
@MainThread
internal class DryHandoff(
    private val commit: (page: PageId, strokes: List<InkStroke>, onRejected: () -> Unit) -> Unit,
    private val isDrawn: (page: PageId, stroke: InkStroke) -> Boolean,
    private val invalidate: () -> Unit,
    private val removeWet: (Collection<Any>) -> Unit,
    private val newId: () -> ObjectId = ObjectId::random,
) {
    private class Pending(
        val key: Any,
        val page: PageId,
        val stroke: InkStroke,
        val finishedNs: Long,
    ) {
        // What the tiles draw for the stroke: the fragments an erase preview replaces it with, or the stroke itself.
        var standIns: List<InkStroke>? = null
    }

    private val pending = ArrayList<Pending>()

    /** Strokes handed to [commit]. */
    var committedCount = 0
        private set

    /** Wet strokes removed after their committed copy was shown. */
    var removedCount = 0
        private set

    /** Wet strokes still waiting for their tiles. */
    val pendingCount: Int get() = pending.size

    /** Commits [strokes] (from [WetSurface.onFinished]). */
    fun onFinished(strokes: List<WetStroke>) {
        val now = PerfMonitor.clock.monotonicNs()
        for (page in strokes.mapTo(LinkedHashSet()) { it.page }) {
            val batch = strokes.filter { it.page == page }
            val entries = batch.map { Pending(it.key, page, InkStroke.of(newId(), it.spec, it.inputs), now) }
            pending += entries
            committedCount += entries.size
            commit(page, entries.map { it.stroke }) { reject(entries) }
        }
    }

    /** Removes the wet copies of strokes whose committed copies the next frame draws. */
    fun check() {
        if (pending.isEmpty()) return
        val done = pending.filter { p -> p.standIns?.all { isDrawn(p.page, it) } ?: isDrawn(p.page, p.stroke) }
        if (done.isEmpty()) return
        pending.removeAll(done.toSet())
        invalidate()
        removeWet(done.map { it.key })
        removedCount += done.size
        val now = PerfMonitor.clock.monotonicNs()
        for (p in done) PerfMonitor.record(SECTION_HANDOFF, now - p.finishedNs)
    }

    /**
     * [body] with the strokes still pending on its page appended (the commit adds them on top), so an erase
     * gesture right after a pen lift can hit them (D-022).
     */
    fun withPending(body: Page): Page {
        if (pending.none { it.page == body.id }) return body
        val ids = body.objects.mapTo(HashSet()) { it.id }
        val missing = pending.filter { it.page == body.id && it.stroke.id !in ids }
        if (missing.isEmpty()) return body
        return body.copy(objects = body.objects.addAll(missing.map { it.stroke }))
    }

    /** The erase preview shown now (null: none, or canceled): pending strokes it replaces wait for its fragments. */
    fun erasing(preview: EraseResult?) {
        for (p in pending) p.standIns = if (preview?.pageId == p.page) preview.replacements[p.stroke.id] else null
    }

    /**
     * Forgets pending strokes and removes their wet copies (view detached): their commits still run, and
     * a reattached view draws them from the tiles, not twice.
     */
    fun clear() {
        if (pending.isEmpty()) return
        removeWet(pending.map { it.key })
        pending.clear()
    }

    private fun reject(entries: List<Pending>) {
        FolioLog.w(TAG, "commit of ${entries.size} strokes on ${entries[0].page.value} rejected; wet strokes dropped")
        pending.removeAll(entries.toSet())
        removeWet(entries.map { it.key })
    }

    /** PerfMonitor sections. */
    companion object {
        /** Main-thread work per finished-strokes callback: conversion plus the synchronous part of the commit. */
        const val SECTION_COMMIT = "ink:commit"

        /** From the finished callback until the wet stroke is removed (committed copy on screen). */
        const val SECTION_HANDOFF = "ink:handoff"

        private const val TAG = "DryHandoff"
    }
}
