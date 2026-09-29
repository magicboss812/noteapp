package dev.folio.core.format

/**
 * Id checks for untrusted files. Document, page and flow ids become parts of entry and working-copy
 * paths, asset ids become entry names: anything else than these shapes is `Corrupt` (P02 REVIEW).
 */
object FolioIds {
    private val SAFE = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
    private val SHA256_HEX = Regex("[0-9a-f]{64}")

    /** Doc/page/flow id: 1..128 chars of `[A-Za-z0-9_-]`, not starting with `-` or `_` (UUIDv4 in practice). */
    fun isSafeId(id: String): Boolean = SAFE.matches(id)

    /** Asset id: lowercase hex SHA-256. */
    fun isAssetId(id: String): Boolean = SHA256_HEX.matches(id)
}
