plugins {
    id("folio.android.library")
    id("folio.compose")
    id("folio.hilt")
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
    implementation(projects.core.storage)
    implementation(libs.ink.authoring)
    implementation(libs.coroutines.android)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    testImplementation(projects.core.testing)
    testImplementation(libs.room.runtime) // in-memory index of the real storage stack in EditorViewModelTest
}
