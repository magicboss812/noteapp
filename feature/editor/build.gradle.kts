plugins {
    id("folio.android.library")
    id("folio.compose")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.feature.editor"
}

dependencies {
    // First on the test classpath: host libink.so, so Robolectric tests render committed strokes into tiles
    testImplementation(libs.ink.nativeloader.jvm)
    implementation(projects.core.designsystem)
    implementation(projects.core.render)
    implementation(projects.core.ink)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.ink.authoring)
    implementation(libs.coroutines.android)
    testImplementation(projects.core.testing)
}
