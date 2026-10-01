plugins {
    id("folio.android.library")
    id("folio.compose")
    id("folio.hilt")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.feature.library"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.storage)
    implementation(projects.core.common)
    implementation(libs.collections.immutable)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    testImplementation(projects.core.testing)
    testImplementation(libs.room.runtime)
}
