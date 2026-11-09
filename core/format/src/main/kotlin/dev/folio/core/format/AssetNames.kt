package dev.folio.core.format

import dev.folio.core.format.manifest.ManifestValues
import dev.folio.core.model.AssetId
import dev.folio.core.model.AssetInfo
import java.security.MessageDigest

/** Asset identity (04-file-format.md#assets): the id is the lowercase hex SHA-256 of the bytes, so equal bytes are one asset. */
object AssetNames {
    private const val HEX = "0123456789abcdef"
    private const val NIBBLE = 4
    private const val LOW_NIBBLE = 0xF

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val out = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt()
            out.append(HEX[v shr NIBBLE and LOW_NIBBLE]).append(HEX[v and LOW_NIBBLE])
        }
        return out.toString()
    }

    /** The asset of [bytes] with [mime] and the file name it came from. */
    fun info(
        bytes: ByteArray,
        mime: String,
        originalName: String?,
    ): AssetInfo = AssetInfo(AssetId(sha256Hex(bytes)), mime, bytes.size.toLong(), originalName)

    /** Container entry name of [info]: `assets/<sha256>.<ext>`. */
    fun entry(info: AssetInfo): String = ManifestValues.assetPath(info)
}
