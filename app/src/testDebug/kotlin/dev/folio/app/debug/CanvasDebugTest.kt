package dev.folio.app.debug

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.TemplateKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

class CanvasDebugTest {
    private val canvas =
        CanvasDebug({
            error("unused")
        }, { error("unused") }, CoroutineScope(Dispatchers.Unconfined), 2.5f, Dispatchers.Unconfined, Dispatchers.Unconfined) {}

    @Test
    fun openTarget_parse_pathGeneratedAndInvalid() {
        assertThat(OpenTarget.parse("notes/a.folio")).isEqualTo(OpenTarget.Path("notes/a.folio"))
        assertThat(OpenTarget.parse("notes:a.folio")).isEqualTo(OpenTarget.Path("notes:a.folio"))
        assertThat(OpenTarget.parse("blank:20")).isEqualTo(OpenTarget.Generated(20))
        assertThat((OpenTarget.parse("blank:20") as OpenTarget.Generated).path).isEqualTo("perf/blank-20.folio")
        assertThat(OpenTarget.parse("planner_daily:3")).isEqualTo(OpenTarget.Generated(3, TemplateKind.PLANNER_DAILY))
        assertThat((OpenTarget.parse("Lined:5") as OpenTarget.Generated).path).isEqualTo("perf/lined-5.folio")
        assertThat(OpenTarget.parse("custom:2")).isEqualTo(OpenTarget.Path("custom:2"))
        assertThat(OpenTarget.parse("blank:0")).isNull()
        assertThat(OpenTarget.parse("blank:x")).isNull()
        assertThat(OpenTarget.parse(null)).isNull()
    }

    @Test
    fun zoomAnim_parse_acceptsInRangeOnly() {
        assertThat(ZoomAnim.parse("1,3,800")).isEqualTo(ZoomAnim(1f, 3f, 800L))
        assertThat(ZoomAnim.parse("0.1,3,800")).isNull()
        assertThat(ZoomAnim.parse("1,9,800")).isNull()
        assertThat(ZoomAnim.parse("1,3")).isNull()
        assertThat(ZoomAnim.parse("1,3,-5")).isNull()
    }

    @Test
    fun scrollPage_parse_pageAndOptionalDuration() {
        assertThat(ScrollPage.parse("5")).isEqualTo(ScrollPage(5, 0L))
        assertThat(ScrollPage.parse("20,3000")).isEqualTo(ScrollPage(20, 3000L))
        assertThat(ScrollPage.parse("0")).isNull()
        assertThat(ScrollPage.parse("2,x")).isNull()
        assertThat(ScrollPage.parse(null)).isNull()
    }

    @Test
    fun seedStrokes_parse_countAndOptionalPage() {
        assertThat(SeedStrokes.parse("1500")).isEqualTo(SeedStrokes(1500, 1))
        assertThat(SeedStrokes.parse("20,3")).isEqualTo(SeedStrokes(20, 3))
        assertThat(SeedStrokes.parse("0")).isNull()
        assertThat(SeedStrokes.parse("5001")).isNull()
        assertThat(SeedStrokes.parse("10,0")).isNull()
        assertThat(SeedStrokes.parse(null)).isNull()
    }

    @Test
    fun syntheticStrokes_sameSeed_sameStrokesInsideThePage() {
        val a = SyntheticStrokes.generate(200, 595f, 842f)
        val b = SyntheticStrokes.generate(200, 595f, 842f)

        assertThat(a).isEqualTo(b)
        assertThat(a.map { it.id }.toSet()).hasSize(200)
        assertThat(a.all { it.bounds.left >= 0f && it.bounds.right <= 595f && it.bounds.bottom <= 842f }).isTrue()
    }

    @Test
    fun commands_noCanvasShown_replyNotOk() {
        assertThat(canvas.zoomAnim("1,3,800").ok).isFalse()
        assertThat(canvas.scrollPage("3").ok).isFalse()
        assertThat(canvas.seedStrokes("10").ok).isFalse()
        assertThat(canvas.open("blank:0").ok).isFalse()
        assertThat(canvas.json()).isNull()
    }
}
