package dev.folio.core.format.manifest

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * `manifest.json` (04-file-format.md#manifest). Keys beyond the documented example are additive
 * (A-011): `background`/`origin` on pages and defaults, so a document opens without decoding pages.
 */
@Serializable
data class Manifest(
    val format: String = FORMAT_NAME,
    val formatVersion: Int,
    val id: String,
    val title: String,
    val createdMs: Long,
    val modifiedMs: Long,
    val app: ManifestApp = ManifestApp(),
    val tags: List<String> = emptyList(),
    val favorite: Boolean = false,
    val pages: List<ManifestPage> = emptyList(),
    val flows: List<String> = emptyList(),
    val assets: List<ManifestAsset> = emptyList(),
    val links: List<String> = emptyList(),
    val textPreview: String = "",
    val defaults: ManifestDefaults = ManifestDefaults(),
) {
    /** Constants of the container format. */
    companion object {
        /** Value of the `format` key. */
        const val FORMAT_NAME = "folio"

        /** Highest major version this app reads and the version it writes. */
        const val CURRENT_VERSION = 1
    }
}

/** Writer application. */
@Serializable
data class ManifestApp(
    val name: String = "Folio",
    val version: String = "",
)

/** Page summary; `kind` is fixed, custom or infinite (then `origin` is fixed or custom). */
@Serializable
data class ManifestPage(
    val id: String,
    val kind: String,
    val size: String? = null,
    val orientation: String? = null,
    val widthPt: Float,
    val heightPt: Float,
    val origin: String? = null,
    val template: String = "BLANK",
    val pdf: ManifestPdf? = null,
    val contentBounds: List<Float>? = null,
    val background: ManifestBackground? = null,
)

/** Full page background (additive key, A-011). */
@Serializable
data class ManifestBackground(
    val paperArgb: String,
    val template: ManifestTemplate,
    val pdf: ManifestPdf? = null,
)

/** Template geometry. Colors are `#AARRGGBB`. */
@Serializable
data class ManifestTemplate(
    val kind: String,
    val spacingPt: Float,
    val lineArgb: String,
    val marginLeftPt: Float = 0f,
    val marginTopPt: Float = 0f,
    val customAsset: String? = null,
    val customGridPt: Float? = null,
)

/** PDF page used as background. */
@Serializable
data class ManifestPdf(
    val asset: String,
    val pageIndex: Int,
)

/** Asset entry; `path` is the container entry name. */
@Serializable
data class ManifestAsset(
    val id: String,
    val path: String,
    val mime: String,
    val bytes: Long,
    val name: String? = null,
)

/** Defaults for new pages. */
@Serializable
data class ManifestDefaults(
    val kind: String = "fixed",
    val size: String? = "A4",
    val orientation: String? = "portrait",
    val widthPt: Float? = null,
    val heightPt: Float? = null,
    val origin: String? = null,
    val template: String = "LINED",
    val spacingPt: Float = 20.126f,
    val paperArgb: String = "#FFFFFFFF",
    val background: ManifestBackground? = null,
)

/** `flows/<flowId>.json`. */
@Serializable
data class FlowStyleJson(
    val fontFamily: String = "Inter",
    val sizeRatio: Float = 0.45f,
    val paragraphGapLines: Int = 0,
    val align: String = "start",
    val autoContinue: Boolean = true,
)

/** JSON settings for all format JSON: unknown keys ignored, defaults written, human-readable. */
internal val FolioJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }
