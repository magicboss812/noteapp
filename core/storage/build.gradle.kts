plugins {
    id("folio.android.library")
}

android {
    namespace = "dev.folio.core.storage"
}

dependencies {
    implementation(projects.core.format)
    implementation(projects.core.model)
    implementation(projects.core.common)
}
