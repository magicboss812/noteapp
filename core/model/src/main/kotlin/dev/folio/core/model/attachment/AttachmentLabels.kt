package dev.folio.core.model.attachment

import java.util.Locale

/** The icon an attachment chip shows (A-070). */
enum class AttachmentKind { PDF, IMAGE, AUDIO, VIDEO, TEXT, SHEET, ARCHIVE, OTHER }

/** What an attachment chip says about its file (A-070): the icon kind and the size label. */
object AttachmentLabels {
    private const val KIB = 1024.0
    private const val ONE_DECIMAL_BELOW = 10.0
    private const val WHOLE_ROUNDING = 0.5
    private const val DECIMAL_ROUNDING = 0.05
    private val UNITS = arrayOf("KB", "MB", "GB")

    private val ARCHIVE_EXTENSIONS = setOf("zip", "7z", "rar", "tar", "gz", "tgz", "bz2", "xz")
    private val SHEET_EXTENSIONS = setOf("xls", "xlsx", "ods", "csv", "tsv", "numbers")
    private val TEXT_EXTENSIONS = setOf("txt", "md", "rtf", "doc", "docx", "odt", "pages", "tex", "json", "xml", "html", "epub")

    /** The icon kind of a file of type [mime] named [name]; the name's extension decides where the type is generic. */
    fun kindOf(
        mime: String,
        name: String,
    ): AttachmentKind {
        val type = mime.lowercase(Locale.ROOT).substringBefore(';').trim()
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return RULES.firstOrNull { (_, matches) -> matches(type, ext) }?.first ?: AttachmentKind.OTHER
    }

    // First match wins: a sheet or document type also matches the text rule's broader checks.
    private val RULES: List<Pair<AttachmentKind, (String, String) -> Boolean>> =
        listOf(
            AttachmentKind.PDF to { type, ext -> type == "application/pdf" || ext == "pdf" },
            AttachmentKind.IMAGE to { type, _ -> type.startsWith("image/") },
            AttachmentKind.AUDIO to { type, _ -> type.startsWith("audio/") },
            AttachmentKind.VIDEO to { type, _ -> type.startsWith("video/") },
            AttachmentKind.SHEET to { type, ext ->
                "spreadsheet" in type || "excel" in type || type == "text/csv" || ext in SHEET_EXTENSIONS
            },
            AttachmentKind.TEXT to { type, ext ->
                type.startsWith("text/") || "document" in type || "msword" in type || ext in TEXT_EXTENSIONS
            },
            AttachmentKind.ARCHIVE to { type, ext ->
                "zip" in type || "compressed" in type || "tar" in type || ext in ARCHIVE_EXTENSIONS
            },
        )

    /**
     * [bytes] for the chip: "512 B", "1.5 KB", "12 KB", "3.4 MB" (binary units, one decimal below 10); empty when the
     * size is unknown (not above 0).
     */
    fun sizeLabel(bytes: Long): String {
        if (bytes <= 0L) return ""
        if (bytes < KIB) return "$bytes B"
        var value = bytes / KIB
        var unit = 0
        while (value >= KIB && unit < UNITS.lastIndex) {
            value /= KIB
            unit++
        }
        // Rounding may reach the next unit (1023.96 KB -> "1024 KB"); show it as 1 of the next unit instead.
        if (value >= KIB - WHOLE_ROUNDING && unit < UNITS.lastIndex) {
            value /= KIB
            unit++
        }
        val number =
            if (value < ONE_DECIMAL_BELOW - DECIMAL_ROUNDING) {
                String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
            } else {
                String.format(Locale.ROOT, "%.0f", value)
            }
        return "$number ${UNITS[unit]}"
    }
}
