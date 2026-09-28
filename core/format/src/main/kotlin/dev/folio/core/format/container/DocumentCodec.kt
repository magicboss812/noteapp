package dev.folio.core.format.container

import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.common.map
import dev.folio.core.format.codec.CorruptDataException
import dev.folio.core.format.codec.PageCodec
import dev.folio.core.format.manifest.FlowStyleJson
import dev.folio.core.format.manifest.FolioJson
import dev.folio.core.format.manifest.Manifest
import dev.folio.core.format.manifest.ManifestMapper
import dev.folio.core.model.Document
import dev.folio.core.model.FlowId
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.TextFlow
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Container entries -> Document. Works on any [EntryReader] (ZIP file or working copy). Writing: [DocumentEntries]. */
object DocumentCodec {
    /** Parses and version-checks `manifest.json` (unknown keys ignored, migrations applied). */
    fun readManifest(
        reader: EntryReader,
        migrations: MigrationRegistry = MigrationRegistry.DEFAULT,
    ): Outcome<Manifest> =
        reader.read(FolioEntries.MANIFEST, FolioEntries.MAX_MANIFEST_BYTES).flatMap { bytes ->
            parseManifest(bytes.decodeToString(), migrations)
        }

    /** Parses manifest text (see [readManifest]). */
    fun parseManifest(
        text: String,
        migrations: MigrationRegistry = MigrationRegistry.DEFAULT,
    ): Outcome<Manifest> =
        guard(FolioEntries.MANIFEST) {
            val raw = FolioJson.parseToJsonElement(text).jsonObject
            val version = raw["formatVersion"]?.jsonPrimitive?.int ?: throw CorruptDataException("formatVersion missing")
            migrations.plan(version).map { plan ->
                var json: JsonObject = raw
                plan.forEach { json = it.migrateManifest(json) }
                FolioJson.decodeFromJsonElement(Manifest.serializer(), json)
            }
        }

    /** Document with meta, page refs, flows and assets; page bodies only if [loadPages]. */
    fun readDocument(
        reader: EntryReader,
        loadPages: Boolean = false,
        migrations: MigrationRegistry = MigrationRegistry.DEFAULT,
    ): Outcome<Document> =
        readManifest(reader, migrations).flatMap { manifest ->
            guard(FolioEntries.MANIFEST) { Outcome.Success(ManifestMapper.toDocumentShell(manifest)) }.flatMap { shell ->
                readFlows(reader, manifest.flows.map(::FlowId)).flatMap { flows ->
                    val doc = shell.copy(flows = flows.associateBy { it.id }.toPersistentMap())
                    if (loadPages) readAllPages(reader, doc) else Outcome.Success(doc)
                }
            }
        }

    /** Decodes one page body. */
    fun readPage(
        reader: EntryReader,
        id: PageId,
    ): Outcome<Page> {
        val name = FolioEntries.page(id)
        return reader.read(name, FolioEntries.MAX_PAYLOAD_BYTES).flatMap { bytes ->
            PageCodec.decode(bytes, name).flatMap { page ->
                if (page.id == id) Outcome.Success(page) else corrupt(name, "page id ${page.id.value} does not match its entry")
            }
        }
    }

    /** Reads flow Markdown + style. A missing style file falls back to defaults. */
    fun readFlow(
        reader: EntryReader,
        id: FlowId,
    ): Outcome<TextFlow> =
        reader.read(FolioEntries.flowText(id), FolioEntries.MAX_PAYLOAD_BYTES).flatMap { md ->
            val styleName = FolioEntries.flowStyle(id)
            val styleJson =
                if (styleName in reader.names()) {
                    reader.read(styleName, FolioEntries.MAX_MANIFEST_BYTES).flatMap { bytes ->
                        guard(styleName) { Outcome.Success(FolioJson.decodeFromString(FlowStyleJson.serializer(), bytes.decodeToString())) }
                    }
                } else {
                    Outcome.Success(FlowStyleJson())
                }
            styleJson.map { s -> TextFlow(id, md.decodeToString(), ManifestMapper.flowStyleFromJson(s), s.autoContinue) }
        }

    private fun readFlows(
        reader: EntryReader,
        ids: List<FlowId>,
    ): Outcome<List<TextFlow>> {
        val flows = ArrayList<TextFlow>(ids.size)
        for (id in ids) {
            when (val r = readFlow(reader, id)) {
                is Outcome.Success -> flows += r.value
                is Outcome.Failure -> return r
            }
        }
        return Outcome.Success(flows)
    }

    private fun readAllPages(
        reader: EntryReader,
        doc: Document,
    ): Outcome<Document> {
        val bodies = doc.pageBodies.builder()
        for (ref in doc.pages) {
            when (val r = readPage(reader, ref.id)) {
                is Outcome.Success -> bodies[ref.id] = r.value
                is Outcome.Failure -> return r
            }
        }
        return Outcome.Success(doc.copy(pageBodies = bodies.build()))
    }

    // SerializationException and NumberFormatException are IllegalArgumentExceptions.
    private inline fun <T> guard(
        entry: String,
        block: () -> Outcome<T>,
    ): Outcome<T> =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            corrupt(entry, e.message.orEmpty(), e)
        } catch (e: CorruptDataException) {
            corrupt(entry, e.message.orEmpty(), e)
        }
}
