package dev.folio.core.pdf.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** P01-S5 on the tablet via [PdfSpikeProbe], 3 runs; logs one line per run under tag FolioProbe. */
@RunWith(AndroidJUnit4::class)
class PdfSpikeInstrumentedTest {
    @Test
    fun pdfSpike_hundredPages_mergesOverlaysAndLogsTimings() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runs = PdfSpikeProbe.run(context, File(context.cacheDir, "p01-s5"), runs = 3)

        for (run in runs) {
            Log.i("FolioProbe", run.line())
            assertThat(run.merged).isTrue()
            assertThat(run.pages).isEqualTo(PdfSpikeProbe.PAGES)
            assertThat(run.allA4).isTrue()
            assertThat(run.markOnAnnotated).isTrue()
            assertThat(run.markOnPlain).isFalse()
        }
    }
}
