plugins {
    id("folio.android.application")
    id("folio.compose")
    id("folio.hilt")
}

android {
    namespace = "dev.folio.app"
    defaultConfig {
        applicationId = "dev.folio.notes"
        versionCode = 1
        versionName = "0.1.0"
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
}
