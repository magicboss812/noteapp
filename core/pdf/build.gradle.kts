plugins {
    id("folio.android.library")
    id("folio.screenshot") // Robolectric host tests
}

android {
    namespace = "dev.folio.core.pdf"
    // Only the debug package family may be installed on the tablet (docs/notes/device.md#quirks).
    defaultConfig.testApplicationId = "dev.folio.notes.debug.test"
}

dependencies {
    implementation(projects.core.render)
    implementation(projects.core.format)
    implementation(projects.core.model)
    implementation(projects.core.common)
    // ADR-006: unencrypted PDFs need no crypto; BouncyCastle (known CVEs) stays out of the APK.
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    androidTestImplementation(libs.bundles.android.test)
}
