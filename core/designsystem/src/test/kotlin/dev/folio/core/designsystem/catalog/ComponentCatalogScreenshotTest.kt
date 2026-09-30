package dev.folio.core.designsystem.catalog

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolioTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// A catalog canvas, not a device layout: 2x density keeps the golden small.
private const val CATALOG = "w1200dp-h900dp-land-xhdpi"

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = CATALOG)
class ComponentCatalogScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun catalog_light_rendersAllComponents() {
        compose.setContent { FolioTheme(darkTheme = false) { ComponentCatalog() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/ComponentCatalog_light.png")
    }

    @Test
    fun catalog_dark_rendersAllComponents() {
        compose.setContent { FolioTheme(darkTheme = true) { ComponentCatalog() } }
        compose.onRoot().captureRoboImage("src/test/screenshots/ComponentCatalog_dark.png")
    }
}
