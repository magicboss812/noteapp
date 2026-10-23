package dev.folio.core.format.container

import dev.folio.core.model.Document

/**
 * Note links of a document (09-storage-library.md#links): the targets of `[Title](folio://doc/<uuid>)` links in its
 * flows, which the manifest lists as `links` so the index can answer backlinks.
 */
object DocumentLinks {
    /** Link scheme of a note link; the document id follows, then an optional `#page=` fragment. */
    const val DOC_SCHEME = "folio://doc/"

    // `](folio://doc/<id>` with the id up to a fragment, a title or the closing parenthesis.
    private val TARGET = Regex("""\]\(\s*<?folio://doc/([A-Za-z0-9_-]+)""")

    /** Distinct target document ids in text order, without [Document.meta]'s own id. */
    fun targets(doc: Document): List<String> {
        val out = LinkedHashSet<String>()
        for (flow in doc.flows.values) {
            for (match in TARGET.findAll(flow.markdown)) out += match.groupValues[1]
        }
        out -= doc.meta.id.value
        return out.toList()
    }
}
