plugins {
    id("folio.jvm.library")
    application
}

application {
    mainClass.set("dev.folio.tools.icongen.MainKt")
}

// `./gradlew :tools:icongen:run` regenerates FolioIcons.kt in core:designsystem from the pinned Lucide SVGs.
tasks.named<JavaExec>("run") {
    val svgDir = layout.projectDirectory.dir("src/main/resources/lucide").asFile
    val output =
        layout.projectDirectory
            .file(
                "../../core/designsystem/src/main/kotlin/dev/folio/core/designsystem/icon/FolioIcons.kt",
            ).asFile
    args(svgDir.absolutePath, output.canonicalPath)
}
