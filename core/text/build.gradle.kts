plugins {
    id("folio.android.library")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.core.text"
    // Only the debug package family may be installed on the tablet (docs/notes/device.md#quirks).
    defaultConfig.testApplicationId = "dev.folio.notes.debug.test"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.text)
    implementation(libs.compose.runtime) // FontFamily.Resolver returns a State
    implementation(libs.jlatexmath.android) // ADR-007
    androidTestImplementation(libs.bundles.android.test)
}
