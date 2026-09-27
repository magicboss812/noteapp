plugins {
    id("folio.jvm.library")
}

dependencies {
    api(projects.core.common)
    api(libs.junit)
    api(libs.truth)
    api(libs.coroutines.test)
}
