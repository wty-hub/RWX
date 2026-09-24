package io.github.rwx

import de.fabmax.kool.input.KeyCode
import de.fabmax.kool.input.KeyEvent
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.LocalKeyCode
import de.fabmax.kool.util.FrontendScope
import io.github.rwx.ui.component.PlatformTextInputController
import io.github.rwx.ui.component.PlatformTextInputRequest
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Container
import java.awt.MouseInfo
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent as AwtKeyEvent
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.AbstractDocument
import javax.swing.text.AttributeSet
import javax.swing.text.DocumentFilter
import javax.swing.text.Element
import javax.swing.text.StyleConstants

/**
 * Desktop input-method (IME) bridge.
 *
 * AWT input methods only attach to a real, focusable text component hosted by a window that macOS
 * lets become the key window. The Kool UI lives in a borderless, transparent `JWindow` that is
 * owned by the game `JFrame`; an input method never attaches to it, so composition and committed
 * text were lost and only the raw keystrokes (the pinyin letters) reached the Kool field. This
 * controller keeps a 1x1, fully transparent [JTextField] in the **frame** instead, brings the frame
 * forward and focuses that editor while a Kool text field is focused, and forwards the committed
 * text to that Kool field. The input method composes into the editor, so in-progress composition
 * stays in the editor's document as marked text and never reaches
 * [PlatformTextInputRequest.onChange].
 *
 * This mirrors [io.github.rwx.AndroidTextInputController], which does the same with a hidden
 * `EditText`.
 */
internal class DesktopTextInputController(
    private val editorHost: Container,
    private val activateEditorWindow: () -> Unit,
    private val setEditorHasFocus: (Boolean) -> Unit,
    private val restoreFocus: () -> Unit,
    private val sendKey: (KeyCode, Int) -> Unit = ::dispatchKoolKey,
    private val dispatch: (() -> Unit) -> Unit = { action -> FrontendScope.launch { action() } },
) : PlatformTextInputController {

    private val editor = JTextField()
    private val maxLengthFilter = MaxLengthFilter()

    /**
     * The Kool field only hands over its editing state while the platform editor really holds
     * focus. If the window manager never grants focus (focus-stealing prevention on some Linux
     * desktops), the field keeps Kool's own editing instead of becoming unusable.
     */
    @Volatile
    private var editorHasFocus = false

    override val ownsCaret: Boolean get() = editorHasFocus

    override val isEditing: Boolean get() = activeRequest != null

    /** Owner of the Kool field that currently wants text input, null when nothing is edited. */
    @Volatile
    private var activeRequest: PlatformTextInputRequest? = null

    private var suppressTextCallback = false
    private var lastForwardedText = ""
    private var lastCaretRequestId = 0L
    private var lastActivationNanos = 0L
    private var focusAttempts = 0
    private var editorAbandoned = false

    @Volatile
    private var lastRequestNanos = 0L

    /**
     * [showOrUpdate] is called on every frame while a Kool text field is composed. If those calls
     * stop while a request is still active, the field was torn down without reporting focus loss
     * (a scene change): leaving the platform editor focused would swallow all keyboard input, so
     * editing is dropped and focus handed back.
     */
    private val staleEditingTimer = Timer(EDITING_STALE_CHECK_MILLIS) { dropStaleEditing() }.apply {
        start()
    }

    init {
        editor.apply {
            // The editor exists only to give the input method a real text component to compose
            // into; it must never be visible.
            isOpaque = false
            border = null
            highlighter = null
            isFocusable = true
            isRequestFocusEnabled = true
            caretColor = Transparent
            foreground = Transparent
            selectionColor = Transparent
            selectedTextColor = Transparent
            setFocusTraversalKeysEnabled(false)
            setBounds(0, 0, EDITOR_SIZE_PX, EDITOR_SIZE_PX)
        }
        (editor.document as? AbstractDocument)?.setDocumentFilter(maxLengthFilter)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = forwardCommittedText()

            override fun removeUpdate(e: DocumentEvent) = forwardCommittedText()

            override fun changedUpdate(e: DocumentEvent) = forwardCommittedText()
        })
        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: AwtKeyEvent) = handleEditorKeyPressed(event)

            override fun keyTyped(event: AwtKeyEvent) {
                // The platform editor is single line: never let a newline reach its document.
                if (event.keyChar == '\n' || event.keyChar == '\r') {
                    event.consume()
                }
            }
        })
        editor.addCaretListener {
            val request = activeRequest ?: return@addCaretListener
            val caret = editor.caretPosition
            val selectionAnchor = if (caret == editor.selectionStart) editor.selectionEnd else editor.selectionStart
            dispatch { request.onSelectionChanged?.invoke(selectionAnchor, caret) }
        }
        // Registering a listener makes Swing treat the editor as an active input method client and
        // gives us visibility on the composition lifecycle.
        editor.addInputMethodListener(object : InputMethodListener {
            override fun inputMethodTextChanged(event: InputMethodEvent) {
                if (logger.isDebugEnabled) {
                    logger.debug(
                        "IME text changed: committed={} text={}",
                        event.committedCharacterCount,
                        event.text?.let { text ->
                            buildString {
                                var character = text.first()
                                while (character != java.text.AttributedCharacterIterator.DONE) {
                                    append(character)
                                    character = text.next()
                                }
                            }
                        },
                    )
                }
            }

            override fun caretPositionChanged(event: InputMethodEvent) = Unit
        })
        editorHost.add(editor)
    }

    override fun showOrUpdate(request: PlatformTextInputRequest) {
        val previous = activeRequest
        val isNewOwner = previous?.owner !== request.owner
        activeRequest = request
        lastRequestNanos = System.nanoTime()
        runOnEdt {
            if (activeRequest?.owner !== request.owner) return@runOnEdt
            maxLengthFilter.maxLength = request.maxLength.coerceAtLeast(1)
            if (isNewOwner) {
                lastForwardedText = request.text
                lastCaretRequestId = request.caretRequest?.id ?: 0L
                focusAttempts = 0
                editorAbandoned = false
                replaceEditorText(request.text, caretAtEnd = true)
                positionEditorNearPointer()
            } else if (request.text != lastForwardedText) {
                // The Kool side rewrote the text (send-and-clear, filtering, ...): mirror it while
                // keeping the caret the user is editing at.
                lastForwardedText = request.text
                replaceEditorText(request.text, caretAtEnd = false)
            }
            request.caretRequest?.takeIf { it.id != lastCaretRequestId }?.let { caretRequest ->
                lastCaretRequestId = caretRequest.id
                applyEditorSelection(caretRequest.selectionStart, caretRequest.caret)
            }

            val editorFocused = editor.isFocusOwner
            if (editorHasFocus != editorFocused) {
                editorHasFocus = editorFocused
                setEditorHasFocus(editorFocused)
                if (editorFocused) focusAttempts = 0
            }
            if (!editorFocused && !editorAbandoned) {
                // The Kool overlay window cannot host an input method; hand the frame the key
                // window role and move focus onto the editor inside it. Re-arm at most a few times
                // a second so a stray click on the overlay cannot tear down an active composition,
                // and give up after a few attempts so a platform that refuses the focus request
                // falls back to Kool's own editing instead of losing text input entirely.
                val now = System.nanoTime()
                if (isNewOwner || now - lastActivationNanos >= FOCUS_REARM_INTERVAL_NANOS) {
                    lastActivationNanos = now
                    focusAttempts += 1
                    activateEditorWindow()
                    if (editor.requestFocusInWindow()) {
                        editorHasFocus = true
                        focusAttempts = 0
                        setEditorHasFocus(true)
                    } else if (focusAttempts >= MAX_FOCUS_ATTEMPTS) {
                        editorAbandoned = true
                        logger.warn(
                            "Desktop text input editor could not take focus after {} attempts " +
                                "(showing={}, focusOwner={}); falling back to Kool's own text editing, " +
                                "system input method stays unavailable",
                            focusAttempts,
                            editor.isShowing,
                            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                .focusOwner?.javaClass?.name,
                        )
                        setEditorHasFocus(false)
                        restoreFocus()
                    }
                }
            }
        }
    }

    override fun hide(owner: Any) {
        if (activeRequest?.owner !== owner) return
        activeRequest = null
        runOnEdt { endEditing() }
    }

    override fun dismissKeyboard() {
        val request = activeRequest
        activeRequest = null
        runOnEdt { endEditing() }
        // Clear Kool field focus so the next frame does not immediately re-attach the editor.
        if (request != null) {
            dispatch { request.onCancel?.invoke() }
        }
    }

    fun dispose() {
        activeRequest = null
        staleEditingTimer.stop()
        runOnEdt {
            endEditing()
            editorHost.remove(editor)
        }
    }

    private fun endEditing() {
        editorHasFocus = false
        focusAttempts = 0
        editorAbandoned = false
        restoreFocus()
    }

    private fun dropStaleEditing() {
        val request = activeRequest ?: return
        if (System.nanoTime() - lastRequestNanos < STALE_EDITING_NANOS) return
        logger.warn(
            "Kool text field {} stopped requesting input without losing focus; ending desktop editing",
            request.owner.javaClass.name,
        )
        activeRequest = null
        endEditing()
    }

    private fun forwardCommittedText() {
        if (suppressTextCallback) return
        val request = activeRequest ?: return
        val text = committedEditorText()
        if (text == lastForwardedText) return
        lastForwardedText = text
        dispatch { request.onChange(text) }
    }

    private fun handleEditorKeyPressed(event: AwtKeyEvent) {
        when (event.keyCode) {
            AwtKeyEvent.VK_ESCAPE -> {
                event.consume()
                // Do not synthesize Esc into Kool's global input stack. While this editor holds AWT
                // focus the Kool canvas is unfocused, so Esc would skip the focused TextField and
                // hit the app-wide back handler (leave battleroom / jump to main menu). Cancel via
                // the request callback instead, which only clears the field's focus.
                val request = activeRequest
                activeRequest = null
                endEditing()
                dispatch { request?.onCancel?.invoke() }
            }

            AwtKeyEvent.VK_TAB -> {
                event.consume()
                sendKey(KeyboardInput.KEY_TAB, if (event.isShiftDown) KEY_MOD_SHIFT else 0)
            }

            AwtKeyEvent.VK_ENTER -> {
                event.consume()
                val request = activeRequest ?: return
                val text = committedEditorText()
                lastForwardedText = text
                // Unlike Android, Enter keeps desktop editing active so the user can keep typing
                // (chat) instead of dismissing the platform editor.
                dispatch { request.onEnter?.invoke(text) }
            }
        }
    }

    /**
     * Text of the platform editor with any in-progress input-method composition removed. Swing
     * stores marked text in the document with [StyleConstants.ComposedTextAttribute]; forwarding
     * it would put the raw pinyin into the Kool field.
     */
    private fun committedEditorText(): String {
        val full = editor.text
        val root = (editor.document as? AbstractDocument)?.defaultRootElement ?: return full
        val composed = mutableListOf<IntRange>()
        collectComposedRanges(root, composed)
        if (composed.isEmpty()) return full
        composed.sortBy { it.first }
        return buildString(full.length) {
            var cursor = 0
            for (range in composed) {
                val start = range.first.coerceIn(cursor, full.length)
                val end = (range.last + 1).coerceIn(start, full.length)
                if (start > cursor) append(full, cursor, start)
                cursor = end
            }
            if (cursor < full.length) append(full, cursor, full.length)
        }
    }

    private fun collectComposedRanges(element: Element, out: MutableList<IntRange>) {
        if (element.attributes.getAttribute(StyleConstants.ComposedTextAttribute) != null) {
            out += element.startOffset until element.endOffset
        }
        for (index in 0 until element.elementCount) {
            collectComposedRanges(element.getElement(index), out)
        }
    }

    private fun replaceEditorText(text: String, caretAtEnd: Boolean) {
        if (editor.text == text) return
        val requestedCaret = if (caretAtEnd) text.length else editor.caretPosition
        suppressTextCallback = true
        try {
            editor.text = text
            editor.caretPosition = requestedCaret.coerceIn(0, text.length)
        } finally {
            suppressTextCallback = false
        }
    }

    private fun applyEditorSelection(selectionStart: Int, caret: Int) {
        val length = editor.text.length
        val start = selectionStart.coerceIn(0, length)
        val end = caret.coerceIn(0, length)
        // Swing's caret (dot) is the moving end while the mark is the selection anchor; Kool models
        // the same pair as (selectionStart, caretPosition).
        editor.caretPosition = start
        if (start != end) {
            editor.moveCaretPosition(end)
        }
    }

    /** Puts the editor where the user clicked, so the IME candidate window shows up near the field. */
    private fun positionEditorNearPointer() {
        val location = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return
        runCatching {
            SwingUtilities.convertPointFromScreen(location, editorHost)
            val maxX = (editorHost.width - EDITOR_SIZE_PX).coerceAtLeast(0)
            val maxY = (editorHost.height - EDITOR_SIZE_PX).coerceAtLeast(0)
            editor.setLocation(location.x.coerceIn(0, maxX), location.y.coerceIn(0, maxY))
        }
    }

    private fun runOnEdt(action: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) {
            action()
        } else {
            SwingUtilities.invokeLater(action)
        }
    }

    private class MaxLengthFilter : DocumentFilter() {
        var maxLength: Int = Int.MAX_VALUE

        override fun insertString(bypass: FilterBypass, offset: Int, string: String?, attributes: AttributeSet?) {
            val text = string ?: return
            val room = maxLength - bypass.document.length
            if (room <= 0) return
            bypass.insertString(offset, text.take(room), attributes)
        }

        override fun replace(
            bypass: FilterBypass,
            offset: Int,
            length: Int,
            text: String?,
            attributes: AttributeSet?,
        ) {
            val replacement = text ?: ""
            val room = (maxLength - (bypass.document.length - length)).coerceAtLeast(0)
            bypass.replace(offset, length, replacement.take(room), attributes)
        }
    }

    private companion object {
        const val EDITOR_SIZE_PX = 1
        const val KEY_MOD_SHIFT = 1
        const val FOCUS_REARM_INTERVAL_NANOS = 300_000_000L
        const val MAX_FOCUS_ATTEMPTS = 3
        const val EDITING_STALE_CHECK_MILLIS = 500
        const val STALE_EDITING_NANOS = 2_000_000_000L
        val Transparent: Color = Color(0, 0, 0, 0)
        val logger = LoggerFactory.getLogger("Desktop")
    }
}

/** Feeds a synthetic key event into Kool's input stack, used for keys the platform editor swallows. */
internal fun dispatchKoolKey(keyCode: KeyCode, modifiers: Int) {
    val localKeyCode = LocalKeyCode(keyCode.code)
    KeyboardInput.handleKeyEvent(KeyEvent(keyCode, localKeyCode, KeyboardInput.KEY_EV_DOWN, modifiers))
    KeyboardInput.handleKeyEvent(KeyEvent(keyCode, localKeyCode, KeyboardInput.KEY_EV_UP, modifiers))
}
