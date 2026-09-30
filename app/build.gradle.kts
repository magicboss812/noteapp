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
        ndk.abiFilters += "arm64-v8a" // the Pad 7 is arm64; drops unused ABIs of native libraries (ink)
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
    implementation(libs.lifecycle.process)
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    debugImplementation(libs.serialization.json)
    // core:text: the P01 spike-fonts screen (app/src/debug/.../spikes, STATUS D-002).
    debugImplementation(projects.core.model) // debug `open blank:N` builds a NewDocumentSpec (P03-T02)
    debugImplementation(projects.core.render)
    debugImplementation(projects.core.ink) // debug canvas controller's pen brush (P03-T06)
    debugImplementation(projects.core.text)
    testImplementation(projects.core.testing)
    androidTestImplementation(libs.bundles.android.test)
}
