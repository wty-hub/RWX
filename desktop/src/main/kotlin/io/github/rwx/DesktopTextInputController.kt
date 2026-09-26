package io.github.rwx

import de.fabmax.kool.input.KeyCode
import de.fabmax.kool.input.KeyEvent
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.LocalKeyCode
import de.fabmax.kool.util.FrontendScope
import io.github.rwx.ui.component.PlatformCaretRect
import io.github.rwx.ui.component.PlatformTextInputBridge
import io.github.rwx.ui.component.PlatformTextInputController
import io.github.rwx.ui.component.PlatformTextInputRequest
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Graphics
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.font.TextHitInfo
import java.awt.im.InputMethodRequests
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.InputMethodEvent
import java.awt.event.InputMethodListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent as AwtKeyEvent
import javax.swing.JLayeredPane
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
 * IBus/XIM attach to the focused X window. The Kool UI is a transparent overlay `JWindow` sitting
 * on top of the game frame, so the editor must live in that overlay (same X window the user
 * clicked). A field hosted on the frame never receives composition: the overlay is the key window
 * and only pinyin letters reach Kool.
 *
 * While a Kool text field is focused this controller keeps a real [JTextField] in that overlay so
 * the input method can compose, and forwards only committed text to [PlatformTextInputRequest.onChange].
 *
 * The Swing editor is a 1×1 IME target at the caret. Covering the Kool field with an opaque
 * editor replaced the original underline style with a flat Swing box; a transparent full-size
 * field would punch a hole through the overlay window and show the game instead of the Kool UI.
 */
internal class DesktopTextInputController(
    private val editorHost: Container,
    private val activateEditorWindow: () -> Unit,
    private val setEditorHasFocus: (Boolean) -> Unit,
    private val restoreFocus: () -> Unit,
    private val returnKeysToCanvas: () -> Unit = {},
    private val sendKey: (KeyCode, Int) -> Unit = ::dispatchKoolKey,
    private val dispatch: (() -> Unit) -> Unit = { action -> FrontendScope.launch { action() } },
    private val moveImeSpot: (Int, Int) -> Unit = LinuxImeCandidateSpot::move,
    private val isHostActive: () -> Boolean = { isSwingComponentHostActive(editorHost) },
) : PlatformTextInputController {

    private val editor = HiddenImeEditor()
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
    private var lastCaretRect: PlatformCaretRect? = null
    private var lastFieldRect: PlatformCaretRect? = null
    private var lastSpot: Point? = null
    private var lastActivationNanos = 0L
    private var focusAttempts = 0
    private var editorAbandoned = false
    private var focusRequestPending = false

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
            // Focusable only while a Kool field is actually editing.
            isFocusable = false
            isRequestFocusEnabled = true
            enableInputMethods(true)
            setFocusTraversalKeysEnabled(false)
            isVisible = false
            setBounds(0, 0, IME_TARGET_SIZE_PX, IME_TARGET_SIZE_PX)
        }
        (editor.document as? AbstractDocument)?.setDocumentFilter(maxLengthFilter)
        editor.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = forwardCommittedText()

            override fun removeUpdate(e: DocumentEvent) = forwardCommittedText()

            override fun changedUpdate(e: DocumentEvent) = forwardCommittedText()
        })
        editor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(event: AwtKeyEvent) {
                // IBus drops the spot when composition starts, so repeat the last caret on each key.
                lastSpot?.let { publishSpot(it.x, it.y, force = true) }
                handleEditorKeyPressed(event)
            }

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
        editor.addFocusListener(object : FocusAdapter() {
            override fun focusGained(event: FocusEvent) {
                syncEditorFocus(true)
                lastSpot?.let { publishSpot(it.x, it.y, force = true) }
            }

            override fun focusLost(event: FocusEvent) {
                syncEditorFocus(false)
            }
        })
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
        if (editorHost is JLayeredPane) {
            editorHost.add(editor, JLayeredPane.DRAG_LAYER)
        } else {
            editorHost.add(editor)
            editorHost.setComponentZOrder(editor, 0)
        }
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
                focusRequestPending = false
                replaceEditorText(request.text, caretAtEnd = true)
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
            if (isNewOwner || request.caretRect != lastCaretRect || request.fieldRect != lastFieldRect) {
                lastCaretRect = request.caretRect
                lastFieldRect = request.fieldRect
                placeEditor(request.caretRect, request.fieldRect)
            }

            syncEditorFocus(editor.isFocusOwner)
            if (!editor.isFocusOwner && !editorAbandoned && !focusRequestPending && isHostActive()) {
                val now = System.nanoTime()
                if (isNewOwner || now - lastActivationNanos >= FOCUS_REARM_INTERVAL_NANOS) {
                    lastActivationNanos = now
                    requestEditorFocus()
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
        focusRequestPending = false
        lastCaretRect = null
        lastFieldRect = null
        lastSpot = null
        // Drop focus before restoring it. A non-focusable editor cannot remain the key target,
        // which is what was swallowing in-game shortcuts after a text field closed.
        editor.isFocusable = false
        editor.isVisible = false
        restoreFocus()
    }

    private fun syncEditorFocus(focused: Boolean) {
        if (editorHasFocus == focused) return
        editorHasFocus = focused
        setEditorHasFocus(focused)
        if (focused) {
            focusAttempts = 0
            editorAbandoned = false
            focusRequestPending = false
        }
    }

    /**
     * [JTextField.requestFocusInWindow] returning true only means the request was queued. Claiming
     * caret ownership from that value turns off Kool's own typing while the hidden editor still
     * has no keys, so neither English nor Chinese arrives. Ownership follows [isFocusOwner] only.
     */
    private fun requestEditorFocus() {
        if (!isHostActive()) return
        focusAttempts += 1
        focusRequestPending = true
        activateEditorWindow()
        editor.isFocusable = true
        // Wait until the overlay has finished dropping key-window status, then ask for focus.
        // Checking on this same turn is always a miss and used to disable the UI keyboard.
        // Never fall back to requestFocus(): that steals the active window from other apps.
        SwingUtilities.invokeLater {
            if (activeRequest == null || editorAbandoned || !isHostActive()) {
                focusRequestPending = false
                return@invokeLater
            }
            editor.requestFocusInWindow()
            SwingUtilities.invokeLater {
                focusRequestPending = false
                if (activeRequest == null || editorAbandoned || !isHostActive()) return@invokeLater
                if (editor.isFocusOwner) {
                    syncEditorFocus(true)
                    return@invokeLater
                }
                returnKeysToCanvas()
                if (focusAttempts >= MAX_FOCUS_ATTEMPTS) {
                    editorAbandoned = true
                    editor.isFocusable = false
                    logger.warn(
                        "Desktop text input editor could not take focus after {} attempts " +
                            "(showing={}, focusOwner={}); using Kool text editing, " +
                            "system input method stays unavailable",
                        focusAttempts,
                        editor.isShowing,
                        java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
                            .focusOwner?.javaClass?.name,
                    )
                }
            }
        }
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
                // hit the app-wide back handler (leave battleroom / jump to main menu).
                // An in-progress composition is cancelled in place; otherwise the field is dropped
                // and an open dialog (in-game chat) closes.
                if (hasActiveComposition()) {
                    editor.inputContext?.endComposition()
                    return
                }
                val request = activeRequest
                activeRequest = null
                endEditing()
                dispatch {
                    request?.onCancel?.invoke()
                    PlatformTextInputBridge.onEscape?.invoke()
                }
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
    private fun hasActiveComposition(): Boolean = committedEditorText() != editor.text

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

    /** Parks the hidden IME target at the caret so the candidate window stays near the text. */
    private fun placeEditor(caret: PlatformCaretRect?, field: PlatformCaretRect?) {
        val box = caret ?: field
        if (box == null) {
            positionEditorNearPointer()
            return
        }
        positionEditor(box.x.toInt(), box.y.toInt())
        val spotX = (caret?.x ?: box.x).toInt()
        val spotY = ((field?.y ?: caret?.y ?: box.y) + (field?.height ?: caret?.height ?: box.height)).toInt()
        val spot = contentWindowPoint(spotX, spotY)
        publishSpot(spot.x, spot.y)
    }

    /** Puts the IME target where the user clicked, so the candidate window shows up near the field. */
    private fun positionEditorNearPointer() {
        val location = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return
        runCatching {
            SwingUtilities.convertPointFromScreen(location, editorHost)
            positionEditor(location.x, location.y)
            val spot = contentWindowPoint(editor.x, editor.y + editor.height)
            publishSpot(spot.x, spot.y)
        }
    }

    private fun positionEditor(x: Int, y: Int, width: Int = IME_TARGET_SIZE_PX, height: Int = IME_TARGET_SIZE_PX) {
        val hostWidth = editorHost.width.takeIf { it > 0 }
            ?: SwingUtilities.getWindowAncestor(editorHost)?.width
            ?: 0
        val hostHeight = editorHost.height.takeIf { it > 0 }
            ?: SwingUtilities.getWindowAncestor(editorHost)?.height
            ?: 0
        val maxX = (hostWidth - width).coerceAtLeast(0)
        val maxY = (hostHeight - height).coerceAtLeast(0)
        editor.isVisible = true
        editor.setBounds(
            x.coerceIn(0, maxX),
            y.coerceIn(0, maxY),
            width.coerceAtLeast(1),
            height.coerceAtLeast(1),
        )
    }

    /** Caret position relative to the overlay (or host) window, which is the XIC focus window. */
    private fun contentWindowPoint(xInHost: Int, yInHost: Int): Point {
        var x = xInHost
        var y = yInHost
        var current: Component? = editorHost
        while (current != null) {
            val parent = current.parent ?: break
            if (parent is Window) break
            x += current.x
            y += current.y
            current = parent
        }
        return Point(x, y)
    }

    private fun publishSpot(x: Int, y: Int, force: Boolean = false) {
        val previous = lastSpot
        if (!force && previous != null && previous.x == x && previous.y == y) return
        lastSpot = Point(x, y)
        moveImeSpot(x, y)
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
        const val IME_TARGET_SIZE_PX = 1
        const val KEY_MOD_SHIFT = 1
        const val FOCUS_REARM_INTERVAL_NANOS = 300_000_000L
        const val MAX_FOCUS_ATTEMPTS = 3
        const val EDITING_STALE_CHECK_MILLIS = 500
        const val STALE_EDITING_NANOS = 2_000_000_000L
        val logger = LoggerFactory.getLogger("Desktop")
    }
}

/**
 * Focusable XIM client that does not paint. A visible Swing field over the Kool canvas replaces
 * the original field style; this stays in the overlay window so the input method still attaches.
 */
private class HiddenImeEditor : JTextField() {
    init {
        isOpaque = false
        border = null
        background = Color(0, 0, 0, 0)
        foreground = Color(0, 0, 0, 0)
        caretColor = Color(0, 0, 0, 0)
        selectionColor = Color(0, 0, 0, 0)
        selectedTextColor = Color(0, 0, 0, 0)
        disabledTextColor = Color(0, 0, 0, 0)
        margin = java.awt.Insets(0, 0, 0, 0)
    }

    override fun paint(g: Graphics) = Unit

    override fun paintComponent(g: Graphics) = Unit

    override fun paintBorder(g: Graphics) = Unit

    override fun updateUI() {
        super.updateUI()
        isOpaque = false
        border = null
    }

    override fun getInputMethodRequests(): InputMethodRequests {
        val base = super.getInputMethodRequests()
        return object : InputMethodRequests {
            override fun getTextLocation(offset: TextHitInfo?): Rectangle {
                if (isShowing) {
                    val screen = locationOnScreen
                    return Rectangle(screen.x, screen.y, 1, height.coerceAtLeast(1))
                }
                return base.getTextLocation(offset)
            }

            override fun getLocationOffset(x: Int, y: Int): TextHitInfo? = base.getLocationOffset(x, y)

            override fun getInsertPositionOffset(): Int = base.insertPositionOffset

            override fun getCommittedText(
                beginIndex: Int,
                endIndex: Int,
                attributes: Array<out java.text.AttributedCharacterIterator.Attribute>?,
            ): java.text.AttributedCharacterIterator = base.getCommittedText(beginIndex, endIndex, attributes)

            override fun getCommittedTextLength(): Int = base.committedTextLength

            override fun cancelLatestCommittedText(
                attributes: Array<out java.text.AttributedCharacterIterator.Attribute>?,
            ): java.text.AttributedCharacterIterator? = base.cancelLatestCommittedText(attributes)

            override fun getSelectedText(
                attributes: Array<out java.text.AttributedCharacterIterator.Attribute>?,
            ): java.text.AttributedCharacterIterator? = base.getSelectedText(attributes)
        }
    }
}

/** Feeds a synthetic key event into Kool's input stack, used for keys the platform editor swallows. */
internal fun dispatchKoolKey(keyCode: KeyCode, modifiers: Int) {
    val localKeyCode = LocalKeyCode(keyCode.code)
    KeyboardInput.handleKeyEvent(KeyEvent(keyCode, localKeyCode, KeyboardInput.KEY_EV_DOWN, modifiers))
    KeyboardInput.handleKeyEvent(KeyEvent(keyCode, localKeyCode, KeyboardInput.KEY_EV_UP, modifiers))
}
