package dev.folio.core.model

import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentMapOf

/** Text alignment of a flow. */
enum class TextAlign { START, CENTER, JUSTIFIED }

/** Per-flow text style; [sizeRatio] is the target cap height as a fraction of the grid unit U. */
data class FlowStyle(
    val fontFamily: String,
    val sizeRatio: Float,
    val paragraphGapLines: Int,
    val align: TextAlign,
)

/** A Markdown text flow laid out across frames; [autoContinue] adds pages when the text overflows. */
data class TextFlow(
    val id: FlowId,
    val markdown: String,
    val style: FlowStyle,
    val autoContinue: Boolean,
)

/** An asset stored once per document (images, PDFs, attachments, custom templates). */
data class AssetInfo(
    val id: AssetId,
    val mime: String,
    val bytes: Long,
    val originalName: String?,
)

/** Document-level metadata; defaults apply to new pages. */
data class DocumentMeta(
    val id: DocId,
    val title: String,
    val createdMs: Long,
    val modifiedMs: Long,
    val tags: PersistentSet<String>,
    val favorite: Boolean,
    val formatVersion: Int,
    val defaultPageSpec: PageSpec,
    val defaultBackground: Background,
)

/**
 * Immutable document state. [pages] is the page order with summaries; [pageBodies] holds the decoded
 * bodies the session has loaded (A-009). A page without a body is unchanged since it was last written,
 * and commands that touch objects require the body to be present. For a loaded page, the body's spec
 * and background always equal its [PageRef].
 */
data class Document(
    val meta: DocumentMeta,
    val pages: PersistentList<PageRef>,
    val flows: PersistentMap<FlowId, TextFlow>,
    val assets: PersistentMap<AssetId, AssetInfo>,
    val pageBodies: PersistentMap<PageId, Page> = persistentMapOf(),
) {
    /** Index of page [id] in [pages], or -1. */
    fun indexOfPage(id: PageId): Int = pages.indexOfFirst { it.id == id }

    /** Summary of page [id], or null. */
    fun pageRef(id: PageId): PageRef? = pages.firstOrNull { it.id == id }

    /** Loaded body of page [id], or null. */
    fun page(id: PageId): Page? = pageBodies[id]
}
