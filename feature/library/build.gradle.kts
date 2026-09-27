plugins {
    id("folio.android.library")
    id("folio.compose")
}

android {
    namespace = "dev.folio.feature.library"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.common)
}
