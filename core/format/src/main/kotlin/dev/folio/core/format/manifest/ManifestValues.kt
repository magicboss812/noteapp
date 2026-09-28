package dev.folio.core.format.manifest

import dev.folio.core.format.codec.corruptIf
import dev.folio.core.model.AssetInfo

/** Value encodings used in manifest JSON: `#AARRGGBB` colors and asset entry names. */
internal object ManifestValues {
    private const val HEX_RADIX = 16
    private const val ARGB_DIGITS = 8
    private const val RGB_DIGITS = 6
    private const val OPAQUE_ALPHA = 0xFF000000L
    private const val MAX_EXT = 8
    private val MIME_EXTENSIONS =
        mapOf("image/png" to "png", "image/jpeg" to "jpg", "image/webp" to "webp", "application/pdf" to "pdf")

    fun formatArgb(argb: Int): String = "#%08X".format(argb)

    /** Parses `#AARRGGBB` or `#RRGGBB` (opaque); anything else is corrupt. */
    fun parseArgb(text: String): Int {
        val hex = text.removePrefix("#")
        val value = hex.toLongOrNull(HEX_RADIX)
        corruptIf(value == null || (hex.length != ARGB_DIGITS && hex.length != RGB_DIGITS)) { "bad color '$text'" }
        val v = value ?: 0L
        return if (hex.length == RGB_DIGITS) (v or OPAQUE_ALPHA).toInt() else v.toInt()
    }

    /** Container entry name of an asset: `assets/<sha256>.<ext>` with the extension from the mime type. */
    fun assetPath(info: AssetInfo): String = "assets/${info.id.value}.${extensionFor(info)}"

    private fun extensionFor(info: AssetInfo): String =
        MIME_EXTENSIONS[info.mime]
            ?: info.originalName
                ?.substringAfterLast('.', "")
                ?.lowercase()
                ?.takeIf { ext -> ext.isNotEmpty() && ext.length <= MAX_EXT && ext.all { it in 'a'..'z' || it in '0'..'9' } }
            ?: "bin"
}
