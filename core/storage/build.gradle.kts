plugins {
    id("folio.android.library")
    id("folio.hilt")
    id("folio.room")
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
    implementation(libs.datastore.preferences)
    testImplementation(projects.core.testing)
    testImplementation(libs.bundles.robolectric)
}
