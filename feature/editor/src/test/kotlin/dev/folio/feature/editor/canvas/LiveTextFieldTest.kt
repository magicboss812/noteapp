package dev.folio.feature.editor.canvas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.edit.TableCell
import dev.folio.core.text.flow.BlockPlacement
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.core.text.layout.FlowTextStyle
import dev.folio.core.text.layout.LayoutGrid
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.core.text.math.JLatexMathRenderer
import dev.folio.feature.editor.text.FieldEdit
import dev.folio.feature.editor.text.FieldKey
import dev.folio.feature.editor.text.TextFieldSpec
import dev.folio.feature.editor.text.TextFocus
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The live text field over a real block layout (native graphics, so font metrics are the real ones). */
@RunWith(AndroidJUnit4::class)
class LiveTextFieldTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val resolver = createFontFamilyResolver(context)
    private val layouter = BlockLayouter(resolver, FontMetricsCache(resolver))
    private val grid = LayoutGrid(unitPt = 20f, indentStepPt = 20f)
    private val viewport = Viewport(context.resources.displayMetrics.density)
    private val edits = ArrayList<FieldEdit>()
    private val chords = ArrayList<Int>()
    private var escapes = 0
    private val verticals = ArrayList<Boolean>()
    private lateinit var field: LiveTextField

    @Before
    fun setUp() {
        viewport.setPages(ModelFixtures.document(listOf(ModelFixtures.page("p0"))).pages, 1000f, 1400f)
        field = LiveTextField(context)
        field.onEdit = { edits += it }
        field.onShortcut = { keyCode, _, _, _ ->
            chords += keyCode
            keyCode == KeyEvent.KEYCODE_Z
        }
        field.onEscape = { escapes++ }
        field.onVertical = { up, _ -> verticals += up }
    }

    private fun spec(
        source: String,
        caret: Int = 0,
        revision: Int = 1,
        widthPt: Float = 400f,
        with: BlockLayouter = layouter,
    ): TextFieldSpec {
        val block = MarkdownDocument.parse(source).blocks.first()
        val layout = with.layout(block, widthPt, FlowTextStyle(), grid)
        val focus = TextFocus(FlowId("body"), block.id, 0, caret, revision)
        return TextFieldSpec(focus, block, layout, BlockPlacement(0, PageId("p0"), 40f, 100f, widthPt))
    }

    private fun show(spec: TextFieldSpec) = field.show(spec, 0xFF101010.toInt(), viewport, 0)

    // The view lays out at its width so the text layout exists (the vertical keys read it).
    private fun layOut() {
        field.measure(View.MeasureSpec.makeMeasureSpec(field.layoutParams.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
        field.layout(0, 0, field.measuredWidth, field.measuredHeight)
    }

    private fun press(
        keyCode: Int,
        meta: Int = 0,
    ): Boolean = field.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, keyCode, 0, meta))

    private fun input(): InputConnection = checkNotNull(field.onCreateInputConnection(EditorInfo()))

    private fun typedSources(): List<String> = edits.filterIsInstance<FieldEdit.Typed>().map { it.source }

    @Test
    fun show_spec_showsTheSourceAndMakesTheFieldVisible() {
        show(spec("# Title", caret = 3))

        assertThat(field.text.toString()).isEqualTo("# Title")
        assertThat(field.selectionEnd).isEqualTo(3)
        assertThat(field.visibility).isEqualTo(View.VISIBLE)
        assertThat(field.spec).isNotNull()
    }

    @Test
    fun init_systemHandwriting_isOff() {
        assertThat(field.isAutoHandwritingEnabled).isFalse()
    }

    @Test
    fun inputConnection_editorInfo_declaresNoStylusHandwriting() {
        val info = EditorInfo()

        field.onCreateInputConnection(info)

        assertThat(info.isStylusHandwritingEnabled).isFalse()
    }

    @Test
    fun show_zoomedViewport_scalesFontSizeAndLineHeight() {
        val spec = spec("hello")
        show(spec)
        val before = viewport.scale

        viewport.zoomBy(2f, 0f, 0f)
        show(spec)

        val scale = viewport.scale
        assertThat(scale).isGreaterThan(before)
        assertThat(field.textSize).isWithin(0.01f).of(spec.layout.fontSizePt * scale)
        assertThat(field.lineHeight.toFloat()).isWithin(1f).of(spec.layout.boxUnits * spec.layout.unitPt * scale)
    }

    @Test
    fun place_whileZooming_scalesTheFieldWithThePageUntilShownAgain() {
        val spec = spec("hello")
        show(spec)
        val styledSize = field.textSize
        val before = viewport.scale

        viewport.zoomBy(0.5f, 0f, 0f)
        field.place(viewport, 0)
        val midZoom = field.scaleX
        val midSize = field.textSize
        show(spec)

        assertThat(midZoom).isWithin(1e-4f).of(viewport.scale / before)
        assertThat(midSize).isEqualTo(styledSize)
        assertThat(field.scaleX).isEqualTo(1f)
        assertThat(field.textSize).isWithin(0.01f).of(spec.layout.fontSizePt * viewport.scale)
    }

    @Test
    fun show_listItem_dimsTheMarker() {
        show(spec("- item"))

        val text = field.text as Spanned
        val spans = text.getSpans(0, text.length, ForegroundColorSpan::class.java)
        assertThat(spans.map { text.getSpanStart(it) to text.getSpanEnd(it) }).contains(0 to 2)
        assertThat(spans.map { Color.alpha(it.foregroundColor) }.distinct()).containsExactly(DIM_ALPHA)
    }

    @Test
    fun show_sameSpecAgain_keepsTheTypedTextAndCaret() {
        val spec = spec("ab", caret = 2)
        show(spec)
        input().commitText("c", 1)
        field.setSelection(1)

        show(spec)

        assertThat(field.text.toString()).isEqualTo("abc")
        assertThat(field.selectionEnd).isEqualTo(1)
    }

    @Test
    fun show_staleEchoOfAnEarlierKeystroke_keepsTheNewerText() {
        show(spec("ab", caret = 2))
        input().commitText("c", 1)
        input().commitText("d", 1)

        show(spec("abc", caret = 3))

        assertThat(field.text.toString()).isEqualTo("abcd")
        assertThat(field.selectionEnd).isEqualTo(4)
    }

    @Test
    fun show_modelTextNobodyTyped_replacesTheTextWithTheCaretAtTheEnd() {
        show(spec("ab", caret = 2))

        show(spec("zzz", caret = 0))

        assertThat(field.text.toString()).isEqualTo("zzz")
        assertThat(field.selectionEnd).isEqualTo(3)
    }

    @Test
    fun show_newRevision_movesTheCaret() {
        show(spec("hello", caret = 1, revision = 1))
        field.setSelection(4)

        show(spec("hello", caret = 2, revision = 2))

        assertThat(field.selectionEnd).isEqualTo(2)
    }

    @Test
    fun show_sameRevision_leavesTheCaretToTheField() {
        show(spec("hello", caret = 1, revision = 1))
        field.setSelection(4)

        show(spec("hello", caret = 2, revision = 1))

        assertThat(field.selectionEnd).isEqualTo(4)
    }

    @Test
    fun typing_aCharacter_reportsTheWholeSourceAndTheCaret() {
        show(spec("ab", caret = 2))

        input().commitText("c", 1)

        assertThat(edits).containsExactly(FieldEdit.Typed("abc", 3))
    }

    @Test
    fun show_tableCell_showsOnlyTheCellTextOverItsColumn() {
        val spec = spec(TABLE, caret = TABLE.indexOf("yz") + 1)
        val cell = checkNotNull(spec.cell)

        show(spec)

        val scale = viewport.scale
        assertThat(field.text.toString()).isEqualTo("yz")
        assertThat(field.selectionEnd).isEqualTo(1)
        assertThat(field.gravity and Gravity.RELATIVE_HORIZONTAL_GRAVITY_MASK).isEqualTo(Gravity.END)
        assertThat(field.layoutParams.width).isEqualTo((cell.widthPt * scale).toInt())
        assertThat(field.translationX).isWithin(0.5f).of((viewport.pageOriginViewX(0) + (40f + cell.leftPt) * scale).toFloat())
    }

    @Test
    fun typing_inATableCell_reportsTheCellText() {
        show(spec(TABLE, caret = TABLE.indexOf("yz") + 2))

        input().commitText("w", 1)

        assertThat(edits).containsExactly(FieldEdit.Cell(TableCell(1, 1), "yzw", 3))
    }

    @Test
    fun tab_inATableCell_reportsTabForThatCell() {
        show(spec(TABLE, caret = TABLE.indexOf("x")))

        press(KeyEvent.KEYCODE_TAB)

        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.TAB, 0, 0, TableCell(1, 0)))
    }

    @Test
    fun right_atTheEndOfATableCell_reportsRightForThatCell() {
        show(spec(TABLE, caret = TABLE.indexOf("x") + 1))

        val handled = press(KeyEvent.KEYCODE_DPAD_RIGHT)

        assertThat(handled).isTrue()
        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.RIGHT, 1, 1, TableCell(1, 0)))
    }

    @Test
    fun show_otherCellSameRevision_showsThatCellsText() {
        show(spec(TABLE, caret = TABLE.indexOf("x"), revision = 1))

        show(spec(TABLE, caret = TABLE.indexOf("bb"), revision = 1))

        assertThat(field.text.toString()).isEqualTo("bb")
        assertThat(field.selectionEnd).isEqualTo(0)
    }

    @Test
    fun selecting_aRange_reportsItForTheFormattingBar() {
        val selections = ArrayList<Pair<Int, Int>>()
        field.onSelection = { start, end -> selections += start to end }
        show(spec("alpha beta", caret = 0))

        field.setSelection(6, 10)

        assertThat(selections.last()).isEqualTo(6 to 10)
    }

    @Test
    fun showing_theModelSourceBeforeTyping_isNotReportedBack() {
        show(spec("ab", caret = 2))
        show(spec("zzz", caret = 0))

        assertThat(edits).isEmpty()
    }

    @Test
    fun enter_key_reportsEnterWithTheSelection() {
        show(spec("abcd", caret = 2))

        val handled = press(KeyEvent.KEYCODE_ENTER)

        assertThat(handled).isTrue()
        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.ENTER, 2, 2))
        assertThat(field.text.toString()).isEqualTo("abcd")
    }

    @Test
    fun enter_withShift_reportsASoftBreak() {
        show(spec("abcd", caret = 2))

        press(KeyEvent.KEYCODE_ENTER, KeyEvent.META_SHIFT_ON)

        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.SOFT_ENTER, 2, 2))
    }

    @Test
    fun enter_fromTheSoftKeyboard_reportsEnter() {
        show(spec("abcd", caret = 4))

        input().commitText("\n", 1)

        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.ENTER, 4, 4))
        assertThat(field.text.toString()).isEqualTo("abcd")
    }

    @Test
    fun backspace_atTheStart_reportsBackspace() {
        show(spec("abcd", caret = 0))

        press(KeyEvent.KEYCODE_DEL)

        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.BACKSPACE, 0, 0))
    }

    @Test
    fun backspace_insideTheListMarker_reportsBackspace() {
        show(spec("- a", caret = 2))

        press(KeyEvent.KEYCODE_DEL)

        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.BACKSPACE, 2, 2))
    }

    @Test
    fun backspace_fromTheSoftKeyboardAtTheStart_reportsBackspace() {
        show(spec("abcd", caret = 0))

        input().deleteSurroundingText(1, 0)

        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.BACKSPACE, 0, 0))
    }

    @Test
    fun backspace_inTheText_isTheViewsOwnDelete() {
        show(spec("abcd", caret = 2))

        press(KeyEvent.KEYCODE_DEL)

        assertThat(typedSources()).containsExactly("acd")
        assertThat(edits.filterIsInstance<FieldEdit.Key>()).isEmpty()
    }

    @Test
    fun delete_atTheEnd_reportsDelete() {
        show(spec("abcd", caret = 4))

        press(KeyEvent.KEYCODE_FORWARD_DEL)

        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.DELETE, 4, 4))
    }

    @Test
    fun arrows_atTheEdgesOfTheText_reportLeftAndRight() {
        show(spec("ab", caret = 0))
        press(KeyEvent.KEYCODE_DPAD_LEFT)
        field.setSelection(2)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)

        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.LEFT, 0, 0), FieldEdit.Key(FieldKey.RIGHT, 2, 2)).inOrder()
    }

    @Test
    fun arrows_insideTheText_areTheViewsOwnMoves() {
        show(spec("abc", caret = 1))

        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        press(KeyEvent.KEYCODE_DPAD_LEFT)

        assertThat(edits).isEmpty()
    }

    @Test
    fun tab_reportsIndentAndWithShiftOutdent() {
        show(spec("- a", caret = 3))

        press(KeyEvent.KEYCODE_TAB)
        press(KeyEvent.KEYCODE_TAB, KeyEvent.META_SHIFT_ON)

        assertThat(edits).containsExactly(FieldEdit.Key(FieldKey.TAB, 3, 3), FieldEdit.Key(FieldKey.SHIFT_TAB, 3, 3)).inOrder()
    }

    @Test
    fun escape_callsOnEscape() {
        show(spec("ab"))

        press(KeyEvent.KEYCODE_ESCAPE)

        assertThat(escapes).isEqualTo(1)
    }

    @Test
    fun ctrlChord_goesToTheShortcutRegistry() {
        show(spec("ab", caret = 1))

        val handled = press(KeyEvent.KEYCODE_Z, KeyEvent.META_CTRL_ON)

        assertThat(handled).isTrue()
        assertThat(chords).containsExactly(KeyEvent.KEYCODE_Z)
        assertThat(edits).isEmpty()
    }

    @Test
    fun ctrlChord_theRegistryDoesNotKnow_isOfferedToTheRegistryFirst() {
        show(spec("ab", caret = 1))

        press(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON)

        // What the view does with it after that (select all, copy, paste) is the platform's, via its shortcut path.
        assertThat(chords).containsExactly(KeyEvent.KEYCODE_A)
        assertThat(edits.filterIsInstance<FieldEdit.Key>()).isEmpty()
    }

    @Test
    fun up_onTheFirstLine_leavesTheBlockUp() {
        show(spec("abc", caret = 1))
        layOut()

        press(KeyEvent.KEYCODE_DPAD_UP)

        assertThat(verticals).containsExactly(true)
    }

    @Test
    fun down_onAFirstLineOfSeveral_isTheViewsOwnMove() {
        show(spec("word ".repeat(40), caret = 0, widthPt = 120f))
        layOut()

        press(KeyEvent.KEYCODE_DPAD_DOWN)

        assertThat(verticals).isEmpty()
    }

    @Test
    fun down_onTheLastLine_leavesTheBlockDown() {
        show(spec("word ".repeat(40), caret = 200, widthPt = 120f))
        layOut()

        press(KeyEvent.KEYCODE_DPAD_DOWN)

        assertThat(verticals).containsExactly(false)
    }

    @Test
    fun place_blockOnThePage_putsTheFirstBaselineOnTheGridLine() {
        val spec = spec("hello")
        show(spec)

        val scale = viewport.scale
        val baselinePx = viewport.pageOriginViewY(0) + (spec.placement.topPt + spec.layout.baselinePt(0)) * scale
        assertThat(field.translationX.toDouble()).isWithin(0.01).of(viewport.pageOriginViewX(0) + (40f + spec.layout.textLeftPt) * scale)
        assertThat(
            field.translationY +
                field.paint.fontMetricsInt
                    .run { -ascent }
                    .toDouble(),
        ).isWithin(0.01).of(baselinePx)
    }

    @Test
    fun place_afterAPan_movesTheField() {
        show(spec("hello"))
        val before = field.translationY

        viewport.zoomBy(2f, 0f, 0f)
        field.place(viewport, 0)

        assertThat(field.translationY).isNotEqualTo(before)
    }

    @Test
    fun hide_afterShow_removesTheField() {
        show(spec("hello"))

        field.hide()

        assertThat(field.visibility).isEqualTo(View.GONE)
        assertThat(field.spec).isNull()
    }

    @Test
    fun show_invalidLatex_showsTheParserErrorAndHideClearsIt() {
        val math = BlockLayouter(resolver, FontMetricsCache(resolver), math = JLatexMathRenderer(context))

        show(spec("Bad \$\\notacommand{x}\$", with = math))

        assertThat(field.error?.toString()).isNotEmpty()
        field.hide()
        assertThat(field.error).isNull()
    }

    @Test
    fun show_fixedLatex_clearsTheError() {
        val math = BlockLayouter(resolver, FontMetricsCache(resolver), math = JLatexMathRenderer(context))
        show(spec("\$\\notacommand{x}\$", with = math))
        assertThat(field.error).isNotNull()

        show(spec("\$\\sqrt{x}\$", with = math))

        assertThat(field.error).isNull()
    }

    @Test
    fun hide_thenShowTheSameBlock_showsTheModelTextAgain() {
        val spec = spec("hello", caret = 5)
        show(spec)
        input().commitText("!", 1)
        field.hide()

        show(spec)

        assertThat(field.text.toString()).isEqualTo("hello")
        assertThat(field.visibility).isEqualTo(View.VISIBLE)
    }

    @Test
    fun paste_textWithWindowsLineEnds_arrivesWithNewlinesOnly() {
        show(spec("ab", caret = 1))
        val clipboard = checkNotNull(context.getSystemService(ClipboardManager::class.java))
        clipboard.setPrimaryClip(ClipData.newPlainText("t", "x\r\ny"))

        field.onTextContextMenuItem(android.R.id.paste)

        assertThat(field.text.toString()).isEqualTo("ax\nyb")
    }

    @Test
    fun undo_fromTheTextMenu_isLeftToTheSession() {
        show(spec("ab", caret = 2))
        input().commitText("c", 1)

        val handled = field.onTextContextMenuItem(android.R.id.undo)

        assertThat(handled).isTrue()
        assertThat(field.text.toString()).isEqualTo("abc")
    }

    private companion object {
        const val DIM_ALPHA = 102

        // Second column right-aligned.
        const val TABLE = "| a   | bb  |\n| --- | --: |\n| x   | yz  |"
    }
}
