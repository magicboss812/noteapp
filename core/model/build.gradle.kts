plugins {
    id("folio.jvm.library")
}

dependencies {
    api(projects.core.common)
    api(libs.collections.immutable)
    testImplementation(projects.core.testing)
}
