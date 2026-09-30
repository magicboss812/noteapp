plugins {
    id("folio.android.library")
    id("folio.compose")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.core.designsystem"
}

dependencies {
    implementation(projects.core.common)
    api(libs.collections.immutable) // component parameters take ImmutableList
}
