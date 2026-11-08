package dev.folio.feature.editor.canvas

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.text.LineBreaker
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.annotation.MainThread
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.core.graphics.ColorUtils
import dev.folio.core.common.PerfMonitor
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import dev.folio.core.text.edit.BlockEditing
import dev.folio.core.text.edit.TableCell
import dev.folio.core.text.layout.BlockFace
import dev.folio.core.text.layout.typeface
import dev.folio.core.text.markdown.TableAlign
import dev.folio.feature.editor.text.FieldEdit
import dev.folio.feature.editor.text.FieldKey
import dev.folio.feature.editor.text.TextFieldSpec
import dev.folio.feature.editor.text.TextFocus

/**
 * The one live text field (07-text-engine.md#editing): an `EditText` over the focused block, set in the block's face at
 * the zoom of the viewport with the grid's line height, so its lines sit where the rendered block had them. It shows
 * the block source with the markers dimmed. The page draws nothing under it (the canvas leaves the block out).
 *
 * Typing is the field's own: it changes at once and reports [FieldEdit.Typed]; the model follows and its answer
 * ([show] with the new source) is dropped when it is the echo of a keystroke the field already has. Keys that change
 * blocks (Enter, Backspace at the start, Left and Right at the ends, Tab) are not applied here but reported as
 * [FieldEdit.Key]; Up and Down on the first and last line go to [onVertical]; Ctrl and Alt chords go to [onShortcut]
 * (the text view would swallow them, the shortcut registry lives above); Escape to [onEscape]. System stylus
 * handwriting is off: the pen draws ink over the block like anywhere else.
 *
 * In a table the field edits one cell ([TextFieldSpec.cell]): its text over the cell, in the header's or the body's
 * face with the column's alignment. Keystrokes report [FieldEdit.Cell]; keys and the selection are cell offsets.
 */
@MainThread
@Suppress("TooManyFunctions") // text sync, styling, placement and the key paths of one view
internal class LiveTextField(
    context: Context,
) : EditText(context) {
    /** Receives every edit of the field. */
    var onEdit: (FieldEdit) -> Unit = {}

    /** Gets Ctrl and Alt chords (key code, ctrl, shift, alt); true if one ran. */
    var onShortcut: (Int, Boolean, Boolean, Boolean) -> Boolean = { _, _, _, _ -> false }

    /** Escape was pressed. */
    var onEscape: () -> Unit = {}

    /** Up (true) or Down at the first or last line, with the caret's x in the host view, px. */
    var onVertical: (up: Boolean, caretXPx: Float) -> Unit = { _, _ -> }

    /**
     * Gets every selection change (anchor, caret; offsets of the block source) and whether it extends the selection
     * (Shift held, or the field showing a new focus) rather than collapsing it. Nullable: EditText reports a selection
     * from its own constructor, before this class's properties exist.
     */
    var onSelection: ((Int, Int, Boolean) -> Unit)? = null

    /** Shift+arrow (key code) past the field's ends or its first or last line, with the caret's x in the host view, px. */
    var onExtend: (keyCode: Int, caretXPx: Float) -> Unit = { _, _ -> }

    /** Ctrl+A outside a table cell. */
    var onSelectAll: () -> Unit = {}

    /** Copy (false) or cut (true) of a selection that spans blocks: the Markdown for the clipboard, null for none. */
    var onCopy: (cut: Boolean) -> String? = { null }

    // The selection reaches into other blocks (07-text-engine.md#selection-as-built): typing and deleting replace all of
    // it through [onEdit]. Set by a new focus, cleared when the selection collapses into the field.
    private var spanning = false

    // A key with Shift or a sync of a new focus is moving the selection: its changes extend rather than collapse it.
    private var extendingKey = false
    private var syncing = false

    /** Runs in the first draw after the caret moved or the block changed (the host keeps the caret line in view). */
    var onCaretChanged: (() -> Unit)? = null
    private var caretMoved = false

    // Time of the tap that focused the shown block (System.nanoTime), recorded as `text:caret` on the next draw; 0 when none.
    private var tapNs = 0L

    private val resolver: FontFamily.Resolver = createFontFamilyResolver(context)
    private val sent = ArrayList<String>()
    private var shown: TextFieldSpec? = null
    private var shownFocus: TextFocus? = null
    private var shownCell: TableCell? = null
    private var synced: TextFieldSpec? = null
    private var styledFace: BlockFace? = null
    private var styledScale = 0f
    private var styledArgb = 0
    private var styledBoxPx = 0f
    private var styledWidthPx = 0
    private var styledGravity = 0
    private var styledMasked = false
    private var ascentPx = 0f
    private var appliedDims: IntArray? = null
    private val dimSpans = ArrayList<ForegroundColorSpan>()
    private var applying = false

    private val watcher =
        object : TextWatcher {
            override fun beforeTextChanged(
                s: CharSequence,
                start: Int,
                count: Int,
                after: Int,
            ) = Unit

            override fun onTextChanged(
                s: CharSequence,
                start: Int,
                before: Int,
                count: Int,
            ) = Unit

            override fun afterTextChanged(s: Editable) {
                // HOT PATH: per keystroke; the block source String is the one allocation.
                if (!applying) typed(s.toString())
            }
        }

    init {
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        gravity = Gravity.TOP or Gravity.START
        // Literal text: no suggestions or smart punctuation that would rewrite the Markdown being typed.
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        breakStrategy = LineBreaker.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        isAutoHandwritingEnabled = false
        defaultFocusHighlightEnabled = false
        importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO
        isFocusableInTouchMode = true
        visibility = GONE
        layoutParams = FrameLayout.LayoutParams(1, FrameLayout.LayoutParams.WRAP_CONTENT)
        addTextChangedListener(watcher)
    }

    /** The block the field is shown for, null while it is hidden. */
    val spec: TextFieldSpec? get() = shown

    /**
     * Shows [spec] in text color [textArgb] at the zoom of [viewport] on page [pageIndex]. The text and the caret are
     * only touched when the block, the caret revision or the source (not a keystroke's echo) changed. [masked] draws
     * the caret but no text: the tiles still show the block until they are rendered without it (P06-T18).
     */
    fun show(
        spec: TextFieldSpec,
        textArgb: Int,
        viewport: Viewport,
        pageIndex: Int,
        masked: Boolean = false,
    ) {
        val appearing = shown == null
        val startNs = if (appearing || spec !== synced) System.nanoTime() else 0L
        shown = spec
        style(spec, textArgb, viewport.scale, masked)
        // The host shows the same spec again when the document or viewport changes: only a new answer is synced.
        if (spec !== synced) {
            sync(spec)
            synced = spec
            caretMoved = true // a new block or a new layout of it: its caret line may sit elsewhere
        } else if (appliedDims == null && TextUtils.equals(text, spec.text)) {
            dim(spec, replaced = false) // restyled (mask, color): the markers take the new color
        }
        shownFocus = spec.focus
        shownCell = spec.cell?.cell
        place(viewport, pageIndex)
        if (startNs != 0L) PerfMonitor.record(TRACE_SHOW, System.nanoTime() - startNs)
        if (appearing) {
            visibility = VISIBLE
            // Taking focus may move the selection and put it back (TextView): that is not the user collapsing it.
            syncing = true
            try {
                PerfMonitor.trace(TRACE_FOCUS) { requestFocus() }
            } finally {
                syncing = false
            }
            // Asking for the keyboard binds the input method (about 14 ms on the Pad 7, P06-T18): the frame that draws
            // the caret is already scheduled, so a post runs after it.
            post(showKeyboard)
        }
    }

    private val showKeyboard =
        Runnable {
            if (shown != null && isFocused) {
                PerfMonitor.trace(TRACE_IME) {
                    context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, 0)
                }
            }
        }

    /** Records the time from [tapNs] (System.nanoTime of the focusing tap) to the next frame that draws the field. */
    fun traceCaretFrom(tapNs: Long) {
        this.tapNs = tapNs
        invalidate()
    }

    override fun onSelectionChanged(
        selStart: Int,
        selEnd: Int,
    ) {
        super.onSelectionChanged(selStart, selEnd)
        caretMoved = true
        val extending = syncing || extendingKey
        if (!extending) spanning = false
        onSelection?.invoke(selStart, selEnd, extending)
    }

    override fun onDraw(canvas: Canvas) {
        PerfMonitor.trace(TRACE_DRAW) { super.onDraw(canvas) }
        if (tapNs != 0L) {
            PerfMonitor.record(TRACE_CARET, System.nanoTime() - tapNs)
            tapNs = 0L
        }
        // The text layout is current here, so the caret line is where the next frame shows it.
        if (caretMoved) {
            caretMoved = false
            onCaretChanged?.invoke()
        }
    }

    /** Top of the caret's line in the host view, px (the field scales around its top-left corner). */
    fun caretLineTopPx(): Float = translationY + (totalPaddingTop + (layout?.let { it.getLineTop(caretLine(it)) } ?: 0)) * scaleY

    /** Bottom of the caret's line in the host view, px. */
    fun caretLineBottomPx(): Float = translationY + (totalPaddingTop + (layout?.let { it.getLineBottom(caretLine(it)) } ?: height)) * scaleY

    private fun caretLine(layout: Layout): Int = layout.getLineForOffset(selectionEnd.coerceAtLeast(0))

    /** Moves the field with the viewport. */
    fun place(
        viewport: Viewport,
        pageIndex: Int,
    ) {
        // HOT PATH: per viewport change while the field is shown; no allocation.
        val spec = shown ?: return
        val layout = spec.layout
        val cell = spec.cell
        val scale = viewport.scale
        val gutterPt =
            when {
                cell != null -> cell.leftPt
                layout.hasMarker -> layout.markerLeftPt
                else -> layout.textLeftPt
            }
        val firstBaselinePt = cell?.baselinePt ?: layout.baselinePt(0)
        val leftPx = viewport.pageOriginViewX(pageIndex) + (spec.placement.leftPt + gutterPt) * scale
        val baselinePx = viewport.pageOriginViewY(pageIndex) + (spec.placement.topPt + firstBaselinePt) * scale
        // Mid-zoom the field scales as a layer around its top-left corner; [show] sets the real size when the zoom settles.
        val layerScale = if (styledScale > 0f) scale / styledScale else 1f
        pivotX = 0f
        pivotY = 0f
        scaleX = layerScale
        scaleY = layerScale
        translationX = leftPx.toFloat()
        translationY = (baselinePx - ascentPx * layerScale).toFloat()
    }

    /** Removes the field and the keyboard. */
    fun hide() {
        shown = null
        shownFocus = null
        shownCell = null
        synced = null
        sent.clear()
        appliedDims = null
        if (error != null) setError(null, null)
        removeCallbacks(showKeyboard)
        if (visibility != GONE) {
            // The insets controller hides the keyboard even when the focus then lands on another view of the window.
            windowInsetsController?.hide(WindowInsets.Type.ime())
            context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
            clearFocus()
            visibility = GONE
        }
    }

    // The editor leaves (another note opens, the library shows) while a block is focused: the keyboard goes with it.
    override fun onDetachedFromWindow() {
        if (visibility != GONE) hide()
        super.onDetachedFromWindow()
    }

    /** x of the caret in the host view, px (the field sits at the host's origin plus its translation). */
    fun caretXPx(): Float {
        val offset = selectionEnd.coerceAtLeast(0)
        return translationX + (layout?.getPrimaryHorizontal(offset) ?: 0f)
    }

    /** True while the text is drawn transparent (only the caret shows). */
    val isMasked: Boolean get() = styledMasked

    private fun style(
        spec: TextFieldSpec,
        textArgb: Int,
        scale: Float,
        masked: Boolean,
    ) {
        val layout = spec.layout
        val cell = spec.cell
        val boxPx = layout.boxUnits * layout.unitPt * scale
        // A table cell: the header's or the body's face, the cell's text width and its column's alignment.
        val face = if (cell != null) cell.face else layout.face
        val widthPt = cell?.widthPt ?: (spec.placement.widthPt - layout.textLeftPt)
        val widthPx = (widthPt * scale).toInt().coerceAtLeast(1)
        val across =
            when (cell?.align) {
                TableAlign.CENTER -> Gravity.CENTER_HORIZONTAL
                TableAlign.RIGHT -> Gravity.END
                else -> Gravity.START
            }
        val sameBox = widthPx == styledWidthPx && across == styledGravity && masked == styledMasked
        if (isStyled(face, scale, textArgb, boxPx) && sameBox) return
        if (face !== styledFace) typeface = face?.typeface(resolver)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, layout.fontSizePt * scale)
        setLineHeight(TypedValue.COMPLEX_UNIT_PX, boxPx)
        setTextColor(if (masked) Color.TRANSPARENT else textArgb)
        styledMasked = masked
        gravity = Gravity.TOP or across
        ascentPx = -paint.fontMetricsInt.ascent.toFloat()
        (layoutParams as FrameLayout.LayoutParams).width = widthPx
        requestLayout()
        styledFace = face
        styledScale = scale
        styledArgb = textArgb
        styledBoxPx = boxPx
        styledWidthPx = widthPx
        styledGravity = across
        appliedDims = null
    }

    private fun isStyled(
        face: BlockFace?,
        scale: Float,
        textArgb: Int,
        boxPx: Float,
    ): Boolean = face === styledFace && scale == styledScale && (textArgb == styledArgb && boxPx == styledBoxPx)

    // The model's answer for the focused block. A new block or caret revision is shown as it is. Otherwise the answer is
    // the echo of a keystroke the field already has (dropped from the sent list; while newer ones are on their way the
    // text stays), and only a model text that nobody typed replaces the field text.
    private fun sync(spec: TextFieldSpec) {
        val focus = spec.focus
        val before = shownFocus
        val source = spec.text
        val moved =
            before == null || before.flowId != focus.flowId || before.blockId != focus.blockId || before.revision != focus.revision ||
                shownCell != spec.cell?.cell
        var replaced = false
        if (moved) {
            sent.clear()
            syncing = true
            try {
                replaced = replaceText(source)
                val cell = spec.cell
                val caret = (focus.caret - (cell?.start ?: 0)).coerceIn(0, length())
                if (cell == null) setSelection(spec.anchorOffset.coerceIn(0, length()), caret) else setSelection(caret)
                spanning = cell == null && focus.spansBlocks
                // The view reports only a changed selection: the same offset in another cell must still name that cell.
                onSelection?.invoke(selectionStart, selectionEnd, true)
            } finally {
                syncing = false
            }
        } else {
            val echo = sent.indexOf(source)
            if (echo >= 0) repeat(echo + 1) { sent.removeAt(0) }
            if (sent.isEmpty()) {
                replaced = replaceText(source)
                if (replaced) setSelection(length())
            }
        }
        if (TextUtils.equals(text, source)) {
            dim(spec, replaced)
            showMathError(spec)
        }
    }

    // Invalid LaTeX in the block (07-text-engine.md#math): the parser's message as the field's error popup, without
    // the icon (it would narrow the text). Typing hides it; the model's answer for the new source shows it again.
    private fun showMathError(spec: TextFieldSpec) {
        val cell = spec.cell
        val message =
            if (cell != null) {
                cell.mathError
            } else {
                spec.layout.mathErrors
                    .firstOrNull()
                    ?.message
            }
        if (!TextUtils.equals(error, message)) setError(message, null)
    }

    private fun replaceText(source: String): Boolean {
        if (TextUtils.equals(text, source)) return false
        applying = true
        try {
            editableText.replace(0, editableText.length, source)
        } finally {
            applying = false
        }
        return true
    }

    // The markers of the source dimmed to 40% of the text color; reapplied only when they or the text changed.
    private fun dim(
        spec: TextFieldSpec,
        replaced: Boolean,
    ) {
        val ranges = spec.dimmed
        if (!replaced && appliedDims?.contentEquals(ranges) == true) return
        val editable = editableText
        for (span in dimSpans) editable.removeSpan(span)
        dimSpans.clear()
        val color = if (styledMasked) Color.TRANSPARENT else ColorUtils.setAlphaComponent(styledArgb, DIM_ALPHA)
        for (i in 0 until ranges.size - 1 step 2) {
            val start = ranges[i].coerceIn(0, editable.length)
            val end = ranges[i + 1].coerceIn(start, editable.length)
            if (end == start) continue
            val span = ForegroundColorSpan(color)
            editable.setSpan(span, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            dimSpans += span
        }
        appliedDims = ranges
    }

    private fun typed(source: String) {
        sent += source
        if (sent.size > MAX_SENT) sent.removeAt(0)
        val caret = selectionEnd.coerceAtLeast(0)
        val cell = shown?.cell
        onEdit(if (cell != null) FieldEdit.Cell(cell.cell, source, caret) else FieldEdit.Typed(source, caret))
    }

    private fun send(
        key: FieldKey,
        start: Int = selectionStart,
        end: Int = selectionEnd,
    ) = onEdit(FieldEdit.Key(key, minOf(start, end).coerceAtLeast(0), maxOf(start, end).coerceAtLeast(0), shown?.cell?.cell))

    private val isCollapsed: Boolean get() = selectionStart == selectionEnd

    /** A caret at the start of the block or inside its marker: Backspace changes the block structure there. */
    private fun atBlockStart(): Boolean = isCollapsed && selectionStart <= (shown?.markerLength ?: 0)

    override fun onKeyDown(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        onChord(keyCode, event)?.let { return it }
        return (spanning && overSelection(keyCode, event)) || structural(keyCode, event.isShiftPressed) || viewKey(keyCode, event)
    }

    // The view's own handling; with Shift held its selection changes extend the selection.
    private fun viewKey(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        extendingKey = event.isShiftPressed
        try {
            return super.onKeyDown(keyCode, event)
        } finally {
            extendingKey = false
        }
    }

    // Ctrl and Alt chords belong to the shortcut registry (undo is the session's); Ctrl+A, C, X and V are the field's
    // clipboard keys; other chords stay with the view.
    private fun onChord(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean? =
        when {
            !event.isCtrlPressed && !event.isAltPressed -> null
            onShortcut(keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed) -> true
            event.isCtrlPressed && !event.isAltPressed && clipboardKey(keyCode) -> true
            else -> null
        }

    // The view would only take these with a selection inside the field; one that spans blocks may be collapsed here.
    override fun onKeyShortcut(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean = (event.isCtrlPressed && !event.isAltPressed && clipboardKey(keyCode)) || super.onKeyShortcut(keyCode, event)

    private fun clipboardKey(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_A -> onTextContextMenuItem(android.R.id.selectAll)
            KeyEvent.KEYCODE_C -> onTextContextMenuItem(android.R.id.copy)
            KeyEvent.KEYCODE_X -> onTextContextMenuItem(android.R.id.cut)
            KeyEvent.KEYCODE_V -> onTextContextMenuItem(android.R.id.paste)
            else -> false
        }

    // A selection over blocks: a character replaces it, Backspace and Delete remove it (Enter and Tab are structural).
    private fun overSelection(
        keyCode: Int,
        event: KeyEvent,
    ): Boolean {
        if (event.isCtrlPressed || event.isAltPressed || event.isMetaPressed) return false
        if (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) return consume { onEdit(FieldEdit.Replace("")) }
        val char = event.unicodeChar
        if (char == 0 || char and KeyCharacterMap.COMBINING_ACCENT != 0 || Character.isISOControl(char)) return false
        return consume { onEdit(FieldEdit.Replace(String(Character.toChars(char)))) }
    }

    private fun structural(
        keyCode: Int,
        shift: Boolean,
    ): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> consume { send(if (shift) FieldKey.SOFT_ENTER else FieldKey.ENTER) }
            KeyEvent.KEYCODE_TAB -> consume { send(if (shift) FieldKey.SHIFT_TAB else FieldKey.TAB) }
            KeyEvent.KEYCODE_ESCAPE -> consume { onEscape() }
            KeyEvent.KEYCODE_DEL -> atBlockStart() && consume { send(FieldKey.BACKSPACE) }
            KeyEvent.KEYCODE_FORWARD_DEL -> isCollapsed && selectionEnd == length() && consume { send(FieldKey.DELETE) }
            else -> if (shift) extend(keyCode) else arrow(keyCode)
        }

    // Shift+arrows that leave the block (not in a table cell): the selection's caret end goes on into the flow.
    private fun extend(keyCode: Int): Boolean {
        if (shown?.cell != null) return false
        val edge =
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> selectionEnd == 0
                KeyEvent.KEYCODE_DPAD_RIGHT -> selectionEnd == length()
                KeyEvent.KEYCODE_DPAD_UP -> onEdgeLine(up = true)
                KeyEvent.KEYCODE_DPAD_DOWN -> onEdgeLine(up = false)
                else -> false
            }
        return edge && consume { onExtend(keyCode, caretXPx()) }
    }

    // Plain arrows that leave the block: Left at its start, Right at its end, Up and Down on its first and last line.
    private fun arrow(keyCode: Int): Boolean =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> isCollapsed && selectionStart == 0 && consume { send(FieldKey.LEFT) }
            KeyEvent.KEYCODE_DPAD_RIGHT -> isCollapsed && selectionEnd == length() && consume { send(FieldKey.RIGHT) }
            KeyEvent.KEYCODE_DPAD_UP -> onEdgeLine(up = true) && consume { onVertical(true, caretXPx()) }
            KeyEvent.KEYCODE_DPAD_DOWN -> onEdgeLine(up = false) && consume { onVertical(false, caretXPx()) }
            else -> false
        }

    private inline fun consume(action: () -> Unit): Boolean {
        action()
        return true
    }

    private fun onEdgeLine(up: Boolean): Boolean {
        val layout = layout ?: return false
        val line = layout.getLineForOffset(selectionEnd.coerceAtLeast(0))
        return if (up) line == 0 else line == layout.lineCount - 1
    }

    // Soft keyboards send Enter and Backspace as text edits, not as key events.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        // Tells the IME and the system handwriting service that this editor takes no stylus handwriting (API 35).
        outAttrs.setStylusHandwritingEnabled(false)
        return object : InputConnectionWrapper(base, true) {
            override fun commitText(
                text: CharSequence,
                newCursorPosition: Int,
            ): Boolean {
                if (text.length == 1 && text[0] == '\n') return consume { send(FieldKey.ENTER) }
                if (spanning) return consume { onEdit(FieldEdit.Replace(text.toString())) }
                return super.commitText(text, newCursorPosition)
            }

            override fun setComposingText(
                text: CharSequence,
                newCursorPosition: Int,
            ): Boolean {
                if (!spanning) return super.setComposingText(text, newCursorPosition)
                // The composition starts over in the text the model answers with.
                onEdit(FieldEdit.Replace(text.toString()))
                context.getSystemService(InputMethodManager::class.java)?.restartInput(this@LiveTextField)
                return true
            }

            override fun deleteSurroundingText(
                beforeLength: Int,
                afterLength: Int,
            ): Boolean {
                if (spanning) return consume { onEdit(FieldEdit.Replace("")) }
                if (beforeLength == 1 && afterLength == 0 && atBlockStart()) return consume { send(FieldKey.BACKSPACE) }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }
        }
    }

    // Undo belongs to the session. A paste is plain text with the line ends the flow takes (Markdown splits into blocks).
    // Ctrl+A selects the block, then the flow; a selection over blocks copies and cuts as Markdown.
    override fun onTextContextMenuItem(id: Int): Boolean =
        when (id) {
            android.R.id.undo, android.R.id.redo -> {
                true
            }

            android.R.id.paste, android.R.id.pasteAsPlainText -> {
                consume(::pasteClipboard)
            }

            android.R.id.selectAll -> {
                if (shown?.cell == null) consume(onSelectAll) else super.onTextContextMenuItem(id)
            }

            android.R.id.copy, android.R.id.cut -> {
                if (spanning) {
                    consume {
                        copyOut(
                            cut = id == android.R.id.cut,
                        )
                    }
                } else {
                    super.onTextContextMenuItem(id)
                }
            }

            else -> {
                super.onTextContextMenuItem(id)
            }
        }

    private fun copyOut(cut: Boolean) {
        val markdown = onCopy(cut) ?: return
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, markdown))
    }

    private fun pasteClipboard() {
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val pasted = BlockEditing.normalizePaste(clip.getItemAt(0).coerceToText(context).toString())
        if (shown?.cell == null) {
            onEdit(FieldEdit.Replace(pasted))
            return
        }
        val start = minOf(selectionStart, selectionEnd).coerceAtLeast(0)
        val end = maxOf(selectionStart, selectionEnd).coerceAtLeast(0)
        editableText.replace(start, end, pasted)
    }

    private companion object {
        const val DIM_ALPHA = 102 // 40% of 255
        const val MAX_SENT = 64
        const val CLIP_LABEL = "Folio text"

        // Tap to the first frame that draws the focused field (07-text-engine.md#editing budget: 50 ms).
        const val TRACE_CARET = "text:caret"

        // Styling, syncing and placing the field for a new block; then focusing it; then the keyboard request after the frame.
        const val TRACE_SHOW = "text:show"
        const val TRACE_FOCUS = "text:focus"
        const val TRACE_IME = "text:ime"
        const val TRACE_DRAW = "text:field-draw"
    }
}
