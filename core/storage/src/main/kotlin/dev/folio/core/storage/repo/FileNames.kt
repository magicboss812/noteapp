package dev.folio.core.storage.repo

import dev.folio.core.common.FolioFs

/** File names derived from titles (09-storage-library.md#library-layout). */
object FileNames {
    /** `.folio` extension. */
    const val FOLIO_EXT = ".folio"
    private const val MAX_STEM = 120
    private val UNSAFE = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

    /**
     * [title] as a file stem: invalid characters become '-', no leading dots, at most 120 chars and
     * 200 UTF-8 bytes (file systems allow 255 bytes; suffixes like " (conflict ...)" need room), never empty.
     */
    fun stemOf(title: String): String {
        var stem =
            title
                .replace(UNSAFE, "-")
                .trim()
                .trimStart('.')
                .take(MAX_STEM)
        while (stem.encodeToByteArray().size > MAX_STEM_BYTES) {
            stem = stem.dropLast(if (stem.length >= 2 && stem[stem.length - 1].isLowSurrogate()) 2 else 1)
        }
        return stem.trim().ifEmpty { "Untitled" }
    }

    private const val MAX_STEM_BYTES = 200

    /** Library path in [dir] for [stem] + [ext] that does not exist yet: "Name", "Name (2)", "Name (3)", ... */
    fun unique(
        fs: FolioFs,
        dir: String,
        stem: String,
        ext: String,
    ): String {
        val candidates = sequenceOf(stem) + generateSequence(2) { it + 1 }.map { "$stem ($it)" }
        return candidates.map { join(dir, "$it$ext") }.first { !fs.exists(it) }
    }

    /** Joins a library-relative directory and a name ("" is the root). */
    fun join(
        dir: String,
        name: String,
    ): String = if (dir.isEmpty()) name else "$dir/$name"

    /** Parent directory of a library path ("" for the root). */
    fun parentOf(path: String): String = path.substringBeforeLast('/', "")

    /** Last path segment. */
    fun nameOf(path: String): String = path.substringAfterLast('/')
}
