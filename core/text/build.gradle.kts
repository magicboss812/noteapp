plugins {
    id("folio.android.library")
    id("folio.screenshot")
}

android {
    namespace = "dev.folio.core.text"
}

dependencies {
    implementation(projects.core.model)
    implementation(projects.core.common)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.text)
    implementation(libs.compose.runtime) // FontFamily.Resolver returns a State
    androidTestImplementation(libs.bundles.android.test)
}
