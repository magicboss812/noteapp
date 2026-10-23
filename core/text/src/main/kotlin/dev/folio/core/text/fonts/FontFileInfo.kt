package dev.folio.core.text.fonts

import dev.folio.core.common.Outcome
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Family and face of one font file, read from its OpenType `name` table. */
data class FontFileInfo(
    val familyName: String,
    val bold: Boolean,
    val italic: Boolean,
    /** "otf" for CFF outlines, "ttf" for TrueType outlines. */
    val extension: String,
) {
    companion object {
        /** Largest font file accepted for import (untrusted input). */
        const val MAX_FONT_BYTES = 32 * 1024 * 1024

        private const val SFNT_TRUETYPE = 0x00010000
        private const val SFNT_OTTO = 0x4F54544F // "OTTO"
        private const val SFNT_TRUE = 0x74727565 // "true"
        private const val TAG_NAME = 0x6E616D65 // "name"
        private const val TABLE_RECORDS_OFFSET = 12
        private const val TABLE_RECORD_SIZE = 16
        private const val NAME_RECORD_SIZE = 12
        private const val NAME_HEADER_SIZE = 6
        private const val ID_FAMILY = 1
        private const val ID_SUBFAMILY = 2
        private const val ID_TYPO_FAMILY = 16
        private const val ID_TYPO_SUBFAMILY = 17
        private const val PLATFORM_UNICODE = 0
        private const val PLATFORM_MAC = 1
        private const val PLATFORM_WINDOWS = 3
        private const val LANGUAGE_EN_US = 0x409
        private const val MAX_NAME_CHARS = 64
        private const val WINDOWS_UNICODE_FULL = 10
        private const val RANK_UNICODE = 2
        private const val RANK_MAC = 3
        private const val U16_MASK = 0xFFFF
        private const val U32_MASK = 0xFFFFFFFFL

        /** Parses [bytes] as a single TrueType/OpenType font (collections are rejected). */
        fun parse(bytes: ByteArray): Outcome<FontFileInfo> {
            if (bytes.size > MAX_FONT_BYTES) return Outcome.Failure("Font file larger than $MAX_FONT_BYTES bytes")
            if (bytes.size < TABLE_RECORDS_OFFSET) return Outcome.Failure("Not a font file")
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            val extension =
                when (buf.getInt(0)) {
                    SFNT_TRUETYPE, SFNT_TRUE -> "ttf"
                    SFNT_OTTO -> "otf"
                    else -> return Outcome.Failure("Not a TrueType or OpenType font")
                }
            val names = readNames(buf) ?: return Outcome.Failure("Font has no readable name table")
            val family = (names[ID_TYPO_FAMILY] ?: names[ID_FAMILY])?.let(::clean)
            if (family.isNullOrEmpty()) return Outcome.Failure("Font has no family name")
            val face = (names[ID_TYPO_SUBFAMILY] ?: names[ID_SUBFAMILY]).orEmpty().lowercase()
            return Outcome.Success(
                FontFileInfo(
                    familyName = family,
                    bold = "bold" in face || "black" in face || "heavy" in face,
                    italic = "italic" in face || "oblique" in face,
                    extension = extension,
                ),
            )
        }

        /** Best name string per wanted name id, or null when the table is missing or malformed. */
        private fun readNames(buf: ByteBuffer): Map<Int, String>? {
            val table = nameTableOffset(buf) ?: return null
            val count = u16(buf, table + 2)
            val storage = table + u16(buf, table + 4)
            if (table + NAME_HEADER_SIZE + count.toLong() * NAME_RECORD_SIZE > buf.limit()) return null
            val best = HashMap<Int, Pair<Int, String>>()
            for (i in 0 until count) {
                val r = table + NAME_HEADER_SIZE + i * NAME_RECORD_SIZE
                val nameId = u16(buf, r + 6)
                val rank = rank(u16(buf, r), u16(buf, r + 2), u16(buf, r + 4))
                val better = nameId in WANTED_IDS && rank != null && rank < (best[nameId]?.first ?: Int.MAX_VALUE)
                val start = storage + u16(buf, r + 10)
                val length = u16(buf, r + 8)
                if (better && rank != null && start + length <= buf.limit()) {
                    val charset = if (u16(buf, r) == PLATFORM_MAC) Charsets.ISO_8859_1 else Charsets.UTF_16BE
                    best[nameId] = rank to String(buf.array(), start, length, charset)
                }
            }
            return best.mapValues { it.value.second }
        }

        /** Offset of the `name` table if its record and header lie inside the file. */
        private fun nameTableOffset(buf: ByteBuffer): Int? {
            val size = buf.limit()
            val numTables = u16(buf, 4)
            if (TABLE_RECORDS_OFFSET + numTables.toLong() * TABLE_RECORD_SIZE > size) return null
            val record =
                (0 until numTables)
                    .map { TABLE_RECORDS_OFFSET + it * TABLE_RECORD_SIZE }
                    .firstOrNull { buf.getInt(it) == TAG_NAME } ?: return null
            val offset = buf.getInt(record + 8).toLong() and U32_MASK
            val length = buf.getInt(record + 12).toLong() and U32_MASK
            return if (offset + NAME_HEADER_SIZE > size || offset + length > size) null else offset.toInt()
        }

        private fun u16(
            buf: ByteBuffer,
            at: Int,
        ): Int = buf.getShort(at).toInt() and U16_MASK

        private val WANTED_IDS = setOf(ID_FAMILY, ID_SUBFAMILY, ID_TYPO_FAMILY, ID_TYPO_SUBFAMILY)

        /** Windows encodings with UTF-16BE strings: symbol, Unicode BMP, Unicode full. */
        private val WINDOWS_UTF16_ENCODINGS = setOf(0, 1, WINDOWS_UNICODE_FULL)

        /**
         * Lower is better: Windows US English, other Windows, Unicode, Mac Roman; null for records
         * whose strings this parser cannot decode.
         */
        private fun rank(
            platform: Int,
            encoding: Int,
            language: Int,
        ): Int? =
            when {
                platform == PLATFORM_WINDOWS && encoding in WINDOWS_UTF16_ENCODINGS -> if (language == LANGUAGE_EN_US) 0 else 1
                platform == PLATFORM_UNICODE -> RANK_UNICODE
                platform == PLATFORM_MAC && encoding == 0 -> RANK_MAC
                else -> null
            }

        /** Printable, trimmed, bounded family name. */
        private fun clean(name: String): String =
            name
                .filter { !it.isISOControl() }
                .trim()
                .take(MAX_NAME_CHARS)
                .trim()
    }
}
