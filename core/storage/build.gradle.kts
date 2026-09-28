plugins {
    id("folio.android.library")
    id("folio.hilt")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.folio.core.storage"
}

dependencies {
    api(projects.core.format)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.serialization.json)
    testImplementation(projects.core.testing)
}
