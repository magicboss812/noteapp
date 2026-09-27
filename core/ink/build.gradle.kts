plugins {
    id("folio.android.library")
}

android {
    namespace = "dev.folio.core.ink"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
}
