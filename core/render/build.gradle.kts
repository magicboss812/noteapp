plugins {
    id("folio.android.library")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.core.render"
}

dependencies {
    implementation(projects.core.ink)
    implementation(projects.core.text)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.ink.rendering)
    testImplementation(projects.core.testing)
}
