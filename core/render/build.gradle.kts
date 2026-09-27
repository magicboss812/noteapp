plugins {
    id("folio.android.library")
}

android {
    namespace = "dev.folio.core.render"
}

dependencies {
    implementation(projects.core.ink)
    implementation(projects.core.text)
    implementation(projects.core.model)
    implementation(projects.core.common)
}
