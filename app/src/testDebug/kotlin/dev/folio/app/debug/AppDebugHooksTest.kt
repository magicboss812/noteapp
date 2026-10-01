package dev.folio.app.debug

import android.content.Intent
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.app.di.AppModule
import dev.folio.app.nav.AppNavigator
import dev.folio.app.nav.AppRoute
import dev.folio.core.common.FolioLog
import dev.folio.core.storage.library.LibraryAccess
import dev.folio.core.storage.library.LibraryRoot
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration

@RunWith(AndroidJUnit4::class)
class AppDebugHooksTest {
    private val lines = mutableListOf<String>()
    private val hooks =
        AppDebugHooks(
            AppModule.dispatchers(),
            LibraryAccess({ false }, LibraryRoot(File(System.getProperty("java.io.tmpdir"), "folio-debughooks-test"))),
            { error("storage not used by these tests") },
            { error("storage not used by these tests") },
        )

    @Before
    fun setUp() = FolioLog.install { _, tag, message, _ -> if (tag == DebugReply.TAG) lines += message }

    @After
    fun tearDown() = FolioLog.install { _, _, _, _ -> }

    @Test
    fun handleIntent_debugcmdExtras_logsReplyWithNonceAndConsumesCommand() {
        val intent = debugIntent(cmd = "route", arg = "_", nonce = "7")

        hooks.handleIntent(intent)
        hooks.handleIntent(intent)

        assertThat(lines).containsExactly("nonce=7 ok=false {\"error\":\"route needs a name\"}")
        assertThat(intent.hasExtra("folio.debug.cmd")).isFalse()
    }

    @Test
    fun handleIntent_missingNonce_repliesWithNoneNonce() {
        hooks.handleIntent(Intent().putExtra("folio.debug.cmd", "state"))

        assertThat(lines.single()).startsWith("nonce=none ok=true ")
    }

    @Test
    fun handleIntent_docAndRouteExtras_appliedOnceAndConsumed() {
        val intent = Intent().putExtra("folio.debug.doc", "a.folio").putExtra("folio.debug.route", "library")

        hooks.handleIntent(intent)

        assertThat(intent.hasExtra("folio.debug.doc")).isFalse()
        assertThat(intent.hasExtra("folio.debug.route")).isFalse()
        hooks.handleIntent(debugIntent(cmd = "state", arg = "_", nonce = "1"))
        assertThat(lines.single()).contains("\"requestedDoc\":\"a.folio\"")
    }

    @Test
    fun activityDestroyed_overlayOn_leavesNoPendingOverlayWork() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        hooks.attach(controller.get(), AppNavigator())
        hooks.handleIntent(debugIntent(cmd = "overlay", arg = "on", nonce = "1"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))

        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))

        assertThat(shadowOf(Looper.getMainLooper()).nextScheduledTaskTime).isEqualTo(Duration.ZERO)
    }

    @Test
    fun routeLibrary_editorOpen_popsToLibraryAndStateFollowsNavigator() {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val navigator = AppNavigator()
        hooks.attach(controller.get(), navigator)
        navigator.openEditor("perf/lined-5.folio")
        hooks.handleIntent(debugIntent(cmd = "state", arg = "_", nonce = "1"))

        hooks.handleIntent(debugIntent(cmd = "route", arg = "library", nonce = "2"))

        assertThat(lines[0]).contains("\"route\":\"editor\"")
        assertThat(lines[1]).contains("\"route\":\"library\"")
        assertThat(navigator.current).isEqualTo(AppRoute.Library)
        controller.pause().stop().destroy()
    }

    private fun debugIntent(
        cmd: String,
        arg: String,
        nonce: String,
    ) = Intent()
        .putExtra("folio.debug.cmd", cmd)
        .putExtra("folio.debug.arg", arg)
        .putExtra("folio.debug.nonce", nonce)
}
