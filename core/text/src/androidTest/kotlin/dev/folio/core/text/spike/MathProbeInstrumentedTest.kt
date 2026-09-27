package dev.folio.core.text.spike

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.math.MathRenderer
import dev.folio.core.text.math.RaTeXMathRenderer
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P01-S4 on the tablet: parse rate, layout+draw p50/p95, ink vs box, and vector PDF output for
 * RaTeX (A) and jlatexmath (C). Logs tables under tag FolioProbe; the ADR-007 rule is applied by hand.
 */
@RunWith(AndroidJUnit4::class)
class MathProbeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val corpus =
        MathProbe.parseCorpus(
            instrumentation.context.assets
                .open("corpus.txt")
                .bufferedReader()
                .readText(),
        )

    @Test
    fun mathRenderers_corpus_logsProbeTable() {
        val renderers: List<MathRenderer> =
            listOf(RaTeXMathRenderer(instrumentation.targetContext), JLatexMathRenderer(instrumentation.targetContext))
        renderers.forEach { MathProbe.run(it, corpus.take(WARM_UP), repeats = 1) } // class loading, fonts, JIT

        val results = renderers.map { MathProbe.run(it, corpus, repeats = REPEATS) }
        MathProbe.table(results).lines().forEach { Log.i(TAG, it) }
        for (renderer in renderers) {
            val pdf = MathProbe.renderPdf(renderer, corpus)
            Log.i(TAG, "${renderer.name} pdf bytes=${pdf.size} vectorOnly=${MathProbe.isVectorOnly(pdf)}")
        }

        assertThat(results.map { it.total }).containsExactly(corpus.size, corpus.size)
    }

    private companion object {
        const val TAG = "FolioProbe"
        const val WARM_UP = 5
        const val REPEATS = 5
    }
}
