plugins {
    id("folio.android.library")
}

android {
    namespace = "dev.folio.core.pdf"
}

dependencies {
    implementation(projects.core.render)
    implementation(projects.core.format)
    implementation(projects.core.model)
    implementation(projects.core.common)
}
