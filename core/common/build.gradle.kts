plugins {
    id("folio.jvm.library")
}

dependencies {
    api(libs.coroutines.core)
    testImplementation(projects.core.testing)
}
