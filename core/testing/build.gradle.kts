plugins {
    id("folio.jvm.library")
}

// Test helpers; other modules use it only as testImplementation (A-002).
dependencies {
    api(projects.core.common)
    api(projects.core.model)
    api(libs.junit)
    api(libs.truth)
    api(libs.turbine)
    api(libs.coroutines.test)
}
