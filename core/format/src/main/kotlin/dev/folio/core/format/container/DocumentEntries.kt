package dev.folio.core.format.container

import dev.folio.core.format.codec.PageCodec
import dev.folio.core.format.manifest.FlowStyleJson
import dev.folio.core.format.manifest.FolioJson
import dev.folio.core.format.manifest.Manifest
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.format.manifest.ManifestMapper
import dev.folio.core.format.manifest.ManifestValues
import dev.folio.core.model.AssetId
import dev.folio.core.model.Document
import dev.folio.core.model.Page
import dev.folio.core.model.TextFlow
import java.io.InputStream

/** Document -> container entries (manifest, pages, flows, assets, search text). */
object DocumentEntries {
    private const val PREVIEW_CHARS = 300
    private val WHITESPACE = Regex("\\s+")

    /** `manifest.json` bytes for [doc]. */
    fun encodeManifest(
        doc: Document,
        app: ManifestApp,
        links: List<String> = emptyList(),
    ): ByteArray {
        val manifest = ManifestMapper.fromDocument(doc, app, links, textPreview(doc))
        return FolioJson.encodeToString(Manifest.serializer(), manifest).encodeToByteArray()
    }

    /** `flows/<id>.md` and `flows/<id>.json` entries. */
    fun encodeFlow(flow: TextFlow): List<Pair<String, ByteArray>> {
        val style = ManifestMapper.flowStyleToJson(flow.style, flow.autoContinue)
        return listOf(
            FolioEntries.flowText(flow.id) to flow.markdown.encodeToByteArray(),
            FolioEntries.flowStyle(flow.id) to FolioJson.encodeToString(FlowStyleJson.serializer(), style).encodeToByteArray(),
        )
    }

    /** `pages/<id>.pb` entry. */
    fun encodePage(page: Page): Pair<String, ByteArray> = FolioEntries.page(page.id) to PageCodec.encode(page)

    /** Container entry name of asset [id] in [doc] (null if the document has no such asset). */
    fun assetEntry(
        doc: Document,
        id: AssetId,
    ): String? = doc.assets[id]?.let(ManifestValues::assetPath)

    /** `search/text.txt`: title and all flow text. */
    fun searchText(doc: Document): String =
        buildString {
            appendLine(doc.meta.title)
            doc.flows.values.forEach { appendLine(it.markdown) }
        }

    /** Every entry of a complete document; all page bodies must be loaded and every asset needs a source. */
    fun all(
        doc: Document,
        app: ManifestApp,
        assets: Map<AssetId, () -> InputStream>,
    ): List<WriterEntry> {
        val out = ArrayList<WriterEntry>()
        out += WriterEntry.of(FolioEntries.MANIFEST, encodeManifest(doc, app))
        doc.pages.forEach { ref ->
            val page = checkNotNull(doc.pageBodies[ref.id]) { "page ${ref.id.value} not loaded" }
            val (name, bytes) = encodePage(page)
            out += WriterEntry.of(name, bytes)
        }
        doc.flows.values.forEach { flow -> encodeFlow(flow).forEach { (n, b) -> out += WriterEntry.of(n, b) } }
        doc.assets.values.forEach { info ->
            val source = checkNotNull(assets[info.id]) { "asset ${info.id.value} has no source" }
            out += WriterEntry(ManifestValues.assetPath(info), source)
        }
        out += WriterEntry.of(FolioEntries.SEARCH_TEXT, searchText(doc).encodeToByteArray())
        return out
    }

    private fun textPreview(doc: Document): String =
        doc.flows.values
            .joinToString(" ") { it.markdown }
            .replace(WHITESPACE, " ")
            .trim()
            .take(PREVIEW_CHARS)
}
