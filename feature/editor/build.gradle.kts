plugins {
    id("folio.android.library")
    id("folio.compose")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.feature.editor"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.render)
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(libs.ink.authoring)
    implementation(libs.coroutines.android)
    testImplementation(projects.core.testing)
}
