plugins {
    id("folio.android.library")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.core.text"
    // Only the debug package family may be installed on the tablet (CLAUDE.md), not dev.folio.core.text.test.
    defaultConfig.testApplicationId = "dev.folio.notes.debug.test"
}

androidComponents {
    // P01-S4: the instrumented math probe reads the shared corpus as an asset.
    onVariants { variant ->
        variant.androidTest
            ?.sources
            ?.assets
            ?.addStaticSourceDirectory(rootProject.file("testdata/math").path)
    }
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.text)
    implementation(libs.compose.runtime) // FontFamily.Resolver returns a State
    implementation(libs.ratex.android)
    implementation(libs.jlatexmath.android) // P01-S4 option C; removed by P01-T08 unless chosen
    androidTestImplementation(libs.bundles.android.test)
}
