plugins {
    id("folio.android.application")
    id("folio.compose")
    id("folio.hilt")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.app"
    defaultConfig {
        applicationId = "dev.folio.notes"
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "dev.folio.app.HiltTestRunner"
        buildConfigField("String", "LIBRARY_ROOT", "\"Documents/Folio\"")
        manifestPlaceholders["appLabel"] = "Folio"
        ndk.abiFilters += "arm64-v8a" // the Pad 7 is arm64; ADR-007 (RaTeX .so) and ink natives
    }
    buildTypes {
        debug {
            buildConfigField("String", "LIBRARY_ROOT", "\"Documents/Folio-Debug\"")
            manifestPlaceholders["appLabel"] = "Folio Debug"
        }
    }
}

dependencies {
    implementation(projects.feature.library)
    implementation(projects.feature.editor)
    implementation(projects.feature.settings)
    implementation(projects.core.designsystem)
    implementation(projects.core.storage)
    implementation(projects.core.common)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    debugImplementation(libs.serialization.json)
    // P01 spike screens (app/src/debug/.../spikes); removed or promoted by P01-T08.
    debugImplementation(projects.core.render)
    debugImplementation(projects.core.text)
    debugImplementation(libs.bundles.ink)
    testImplementation(projects.core.testing)
    androidTestImplementation(libs.bundles.android.test)
}
