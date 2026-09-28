plugins {
    id("folio.jvm.library")
    id("folio.wire")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.serialization.json)
    testImplementation(projects.core.testing)
}
