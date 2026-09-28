package dev.folio.core.model.edit

import dev.folio.core.model.AssetId
import dev.folio.core.model.AssetInfo
import dev.folio.core.model.Document
import dev.folio.core.model.DocumentMeta
import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextFlow
import kotlinx.collections.immutable.mutate

/** Replaces the document metadata (title, tags, favorite, defaults). The id cannot change. */
data class UpdateMeta(
    val meta: DocumentMeta,
    override val coalesceKey: String? = null,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = emptySet()

    override fun execute(doc: Document): Applied {
        require(meta.id == doc.meta.id) { "document id cannot change" }
        return Applied(doc.copy(meta = meta), UpdateMeta(doc.meta, coalesceKey), coalesceKey)
    }
}

/** Adds or replaces flows ([put]) and removes flows ([remove]) including style changes. */
data class UpdateFlows(
    val put: List<TextFlow>,
    val remove: List<FlowId> = emptyList(),
    override val coalesceKey: String? = null,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = emptySet()

    override fun execute(doc: Document): Applied {
        val putIds = put.map { it.id }.toHashSet()
        require(putIds.size == put.size && remove.none { it in putIds }) { "flow id listed twice" }
        val restore = (putIds + remove).mapNotNull { doc.flows[it] }
        val drop = putIds.filter { it !in doc.flows }
        require(remove.all { it in doc.flows }) { "unknown flow id" }
        val flows =
            doc.flows.mutate { m ->
                remove.forEach { m.remove(it) }
                put.forEach { m[it.id] = it }
            }
        return Applied(doc.copy(flows = flows), UpdateFlows(restore, drop, coalesceKey), coalesceKey)
    }
}

/** One text replacement in a flow's Markdown: characters [start, end) become [replacement]. */
data class TextEdit(
    val start: Int,
    val end: Int,
    val replacement: String,
)

/** Text edits applied in order to one flow; each range refers to the text after the previous edits. */
data class EditFlow(
    val flowId: FlowId,
    val edits: List<TextEdit>,
    override val coalesceKey: String? = null,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = emptySet()

    override fun execute(doc: Document): Applied {
        val flow = checkNotNull(doc.flows[flowId]) { "unknown flow ${flowId.value}" }
        var text = flow.markdown
        val inverse = ArrayList<TextEdit>(edits.size)
        for (e in edits) {
            require(e.start in 0..e.end && e.end <= text.length) { "edit $e outside 0..${text.length}" }
            inverse += TextEdit(e.start, e.start + e.replacement.length, text.substring(e.start, e.end))
            text = text.replaceRange(e.start, e.end, e.replacement)
        }
        inverse.reverse()
        val newDoc = doc.copy(flows = doc.flows.put(flowId, flow.copy(markdown = text)))
        return Applied(newDoc, EditFlow(flowId, inverse, coalesceKey), coalesceKey)
    }
}

/** Adds or replaces asset entries ([put]) and removes entries ([remove]); bytes live in the container. */
data class UpdateAssets(
    val put: List<AssetInfo>,
    val remove: List<AssetId> = emptyList(),
) : EditCommand {
    override val requiredPages: Set<PageId> get() = emptySet()
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val putIds = put.map { it.id }.toHashSet()
        require(putIds.size == put.size && remove.none { it in putIds }) { "asset id listed twice" }
        require(remove.all { it in doc.assets }) { "unknown asset id" }
        val restore = (putIds + remove).mapNotNull { doc.assets[it] }
        val drop = putIds.filter { it !in doc.assets }
        val assets =
            doc.assets.mutate { m ->
                remove.forEach { m.remove(it) }
                put.forEach { m[it.id] = it }
            }
        return Applied(doc.copy(assets = assets), UpdateAssets(restore, drop), null)
    }
}
