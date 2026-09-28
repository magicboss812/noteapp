plugins {
    id("folio.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.folio.core.storage"
}

dependencies {
    implementation(projects.core.format)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.serialization.json)
    testImplementation(projects.core.testing)
}
