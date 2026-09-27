plugins {
    id("folio.android.library")
    id("folio.compose")
}

android {
    namespace = "dev.folio.core.designsystem"
}

dependencies {
    implementation(projects.core.common)
}
