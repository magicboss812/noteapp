plugins {
    id("folio.android.library")
    id("folio.compose")
}

android {
    namespace = "dev.folio.feature.settings"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.common)
}
