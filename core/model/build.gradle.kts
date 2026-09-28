plugins {
    id("folio.jvm.library")
}

dependencies {
    implementation(projects.core.common)
    api(libs.collections.immutable)
}
