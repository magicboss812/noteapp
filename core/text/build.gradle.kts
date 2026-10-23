plugins {
    id("folio.android.library")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.core.text"
    // Only the debug package family may be installed on the tablet (docs/notes/device.md#quirks).
    defaultConfig.testApplicationId = "dev.folio.notes.debug.test"
}

// The grid invariant suite runs on Robolectric and on the tablet (P06-T03).
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    listOf("test", "androidTest").forEach { sourceSets.getByName(it).kotlin.srcDir("src/sharedTest/kotlin") }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.text)
    implementation(libs.compose.runtime) // FontFamily.Resolver returns a State
    implementation(libs.jlatexmath.android) // ADR-007
    implementation(libs.bundles.commonmark)
    testImplementation(projects.core.testing)
    androidTestImplementation(libs.bundles.android.test)
}
