plugins {
    id("folio.jvm.library")
    id("folio.wire")
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    testImplementation(projects.core.testing)
}
