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
    debugImplementation(libs.serialization.json)
    testImplementation(projects.core.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    androidTestImplementation(libs.truth)
    kspAndroidTest(libs.hilt.compiler)
}
