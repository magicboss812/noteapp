package dev.folio.tools.icongen

import java.io.File

/**
 * Lucide SVG -> Compose ImageVector generator (11-design-system.md#icons).
 * Usage: icongen <lucide svg dir> <output FolioIcons.kt>. The svg dir holds the pinned release's SVGs and a
 * VERSION file with the Lucide tag; `./gradlew :tools:icongen:run` passes both paths.
 */
fun main(args: Array<String>) {
    require(args.size == 2) { "usage: icongen <lucide svg dir> <output FolioIcons.kt>" }
    val svgDir = File(args[0])
    val output = File(args[1])
    val version = File(svgDir, "VERSION").readText().trim()
    val icons =
        svgDir
            .listFiles { file -> file.extension == "svg" }
            .orEmpty()
            .sortedBy { it.name }
            .map { LucideIcon(it.nameWithoutExtension, SvgConverter.convert(it.readText())) }
    require(icons.isNotEmpty()) { "no SVG files in $svgDir" }
    output.parentFile.mkdirs()
    output.writeText(KotlinEmitter.emit(icons, version))
    println("icongen: ${icons.size} icons (Lucide $version) -> $output")
}
