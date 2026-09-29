package dev.folio.core.format.container

import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId

/** Entry names and layout rules of the `.folio` ZIP (04-file-format.md#container-layout). */
object FolioEntries {
    /** First entry, STORED. */
    const val MIMETYPE = "mimetype"

    /** Content of [MIMETYPE]. */
    const val MIME_TYPE_VALUE = "application/vnd.folio+zip"

    /** Document metadata. */
    const val MANIFEST = "manifest.json"

    /** Plain text for the search index. */
    const val SEARCH_TEXT = "search/text.txt"

    /** Library cover thumbnail. */
    const val COVER = "thumbs/cover.webp"

    /** Largest manifest accepted (untrusted input bound). */
    const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024

    /** Largest flow or text entry accepted. */
    const val MAX_PAYLOAD_BYTES = 64L * 1024 * 1024

    /** Largest page entry accepted: Wire boxes repeated ints, so decoding needs many times the size. */
    const val MAX_PAGE_BYTES = 16L * 1024 * 1024

    private const val MAX_NAME_LENGTH = 255
    private const val ASCII_MAX = 0x7F
    private const val PRINTABLE_MIN = 0x20

    /** Page body entry. */
    fun page(id: PageId): String = "pages/${id.value}.pb"

    /** Flow Markdown entry. */
    fun flowText(id: FlowId): String = "flows/${id.value}.md"

    /** Flow style entry. */
    fun flowStyle(id: FlowId): String = "flows/${id.value}.json"

    /** Page thumbnail entry. */
    fun pageThumb(id: PageId): String = "thumbs/${id.value}.webp"

    /** Safe relative entry name: printable ASCII, no absolute path, no backslash, no `.`/`..` segments. */
    fun isValidName(name: String): Boolean =
        name.isNotEmpty() &&
            name.length <= MAX_NAME_LENGTH &&
            name.all { it.code in PRINTABLE_MIN..ASCII_MAX && it != '\\' } &&
            !name.startsWith("/") &&
            !name.endsWith("/") &&
            name.split('/').none { it.isEmpty() || it == "." || it == ".." }

    /** Assets and thumbnails are already compressed: STORED. Everything else DEFLATED level 6. */
    fun isStored(name: String): Boolean = name == MIMETYPE || name.startsWith("assets/") || name.startsWith("thumbs/")

    /** Names in container order: mimetype, manifest, pages, flows, assets, thumbs, search, then unknown entries. */
    fun ordered(names: Collection<String>): List<String> = names.sortedWith(compareBy<String>({ rank(it) }, { it }))

    // Container layout order; unknown entries (preserved from newer writers) go last.
    private val ORDER: List<(String) -> Boolean> =
        listOf(
            { it == MIMETYPE },
            { it == MANIFEST },
            { it.startsWith("pages/") },
            { it.startsWith("flows/") },
            { it.startsWith("assets/") },
            { it.startsWith("thumbs/") },
            { it == SEARCH_TEXT },
        )

    private fun rank(name: String): Int = ORDER.indexOfFirst { it(name) }.let { if (it < 0) ORDER.size else it }
}
