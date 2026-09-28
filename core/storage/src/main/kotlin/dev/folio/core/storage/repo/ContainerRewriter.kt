package dev.folio.core.storage.repo

import dev.folio.core.common.FolioFs
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.common.outcomeOf
import dev.folio.core.format.container.FolioContainerReader
import dev.folio.core.format.container.FolioContainerWriter
import dev.folio.core.format.container.FolioEntries
import dev.folio.core.format.container.WriterEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.IOException

/**
 * Metadata change applied directly to `manifest.json` JSON, so keys this version does not know are
 * kept (04-file-format.md#versioning). Null fields stay unchanged.
 */
data class ManifestPatch(
    val id: String? = null,
    val title: String? = null,
    val favorite: Boolean? = null,
    val tags: List<String>? = null,
    val createdMs: Long? = null,
    val modifiedMs: Long? = null,
) {
    internal fun applyTo(manifest: JsonObject): JsonObject {
        val out = LinkedHashMap<String, JsonElement>(manifest)
        id?.let { out["id"] = JsonPrimitive(it) }
        title?.let { out["title"] = JsonPrimitive(it) }
        favorite?.let { out["favorite"] = JsonPrimitive(it) }
        tags?.let { t -> out["tags"] = JsonArray(t.map(::JsonPrimitive)) }
        createdMs?.let { out["createdMs"] = JsonPrimitive(it) }
        modifiedMs?.let { out["modifiedMs"] = JsonPrimitive(it) }
        return JsonObject(out)
    }
}

/**
 * Rewrites a closed `.folio` with a patched manifest (and, for a new title, the first line of
 * `search/text.txt`), streaming all other entries unchanged. [target] may equal [source]: the new file
 * replaces it atomically. Documents open in a session change through the session instead.
 */
internal class ContainerRewriter(
    private val fs: FolioFs,
) {
    fun rewrite(
        source: String,
        target: String,
        patch: ManifestPatch,
    ): Outcome<Unit> {
        val file = fs.localFile(source) ?: return Outcome.Failure("library is not file-backed")
        return FolioContainerReader.open(file).flatMap { reader ->
            reader.use {
                reader
                    .read(FolioEntries.MANIFEST, FolioEntries.MAX_MANIFEST_BYTES)
                    .flatMap { manifestBytes ->
                        outcomeOf("patch manifest of $source") {
                            val json = Json.parseToJsonElement(manifestBytes.decodeToString()).jsonObject
                            patch.applyTo(json).toString().encodeToByteArray()
                        }
                    }.flatMap { newManifest ->
                        val entries =
                            reader.names().map { name ->
                                when {
                                    name == FolioEntries.MANIFEST -> {
                                        WriterEntry.of(name, newManifest)
                                    }

                                    name == FolioEntries.SEARCH_TEXT && patch.title != null -> {
                                        WriterEntry.of(name, retitle(reader, patch.title))
                                    }

                                    else -> {
                                        WriterEntry(name) {
                                            when (val r = reader.open(name)) {
                                                is Outcome.Success -> r.value
                                                is Outcome.Failure -> throw IOException(r.message, r.cause)
                                            }
                                        }
                                    }
                                }
                            }
                        fs.writeAtomic(target) { out -> FolioContainerWriter.write(out, entries) }
                    }
            }
        }
    }

    private fun retitle(
        reader: FolioContainerReader,
        title: String,
    ): ByteArray {
        val old =
            (
                reader.read(
                    FolioEntries.SEARCH_TEXT,
                    FolioEntries.MAX_PAYLOAD_BYTES,
                ) as? Outcome.Success
            )?.value?.decodeToString().orEmpty()
        val rest = old.substringAfter('\n', "")
        return "$title\n$rest".encodeToByteArray()
    }
}
