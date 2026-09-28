package dev.folio.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.folio.core.storage.LibraryConfig
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class MainActivitySmokeTest {
    private val hilt = HiltAndroidRule(this)
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(hilt).around(compose)

    @Inject
    lateinit var libraryConfig: LibraryConfig

    @Before
    fun inject() = hilt.inject()

    @Test
    fun launch_debugBuild_showsLibraryEntryAndDebugLibraryRoot() {
        // Onboarding or library placeholder, depending on the all-files access state of the device.
        val entry = hasText("Allow file access") or hasText("Library")
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodes(entry).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(entry).onFirst().assertIsDisplayed()
        assertThat(libraryConfig.rootRelativePath).isEqualTo("Documents/Folio-Debug")
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
