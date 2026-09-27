plugins {
    id("folio.jvm.library")
}

// Test helpers; other modules use it only as testImplementation (A-002).
dependencies {
    api(projects.core.common)
    api(libs.junit)
    api(libs.truth)
    api(libs.turbine)
    api(libs.coroutines.test)
}
