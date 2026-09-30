plugins {
    id("folio.android.library")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.core.ink"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    api(libs.ink.brush) // StrokeBuilder returns androidx.ink strokes to core:render and feature:editor
    api(libs.ink.strokes)
    implementation(libs.ink.geometry)
    // First on the test classpath: its NativeLoader loads the host libink.so, so Robolectric tests mesh real strokes
    testImplementation(libs.ink.nativeloader.jvm)
    testImplementation(libs.ink.rendering)
}
