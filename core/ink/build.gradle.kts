plugins {
    id("folio.android.library")
}

android {
    namespace = "dev.folio.core.ink"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    api(libs.ink.brush) // StrokeBuilder returns androidx.ink strokes to core:render and feature:editor
    api(libs.ink.strokes)
}
