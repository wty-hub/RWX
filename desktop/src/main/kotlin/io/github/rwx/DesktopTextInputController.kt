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
import java.awt.Component
import java.awt.Container
import java.awt.MouseInfo
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent as AwtKeyEvent
import javax.swing.JTextField
import javax.swing.SwingUtilities
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
 * AWT input methods only attach to real text components: a bare [java.awt.Canvas] never becomes an
 * active IME client, so composed and committed text never reached the Kool UI and only the raw
 * keystrokes (the pinyin letters) landed in the field. This controller keeps a 1x1, fully
 * transparent [JTextField] inside the Kool overlay window, focuses it while a Kool text field is
 * focused, and forwards the committed text to that Kool field. The input method composes into the
 * editor, so in-progress composition stays in the editor's document as marked text and never
 * reaches [PlatformTextInputRequest.onChange].
 *
 * This mirrors [io.github.rwx.AndroidTextInputController], which does the same with a hidden
 * `EditText`.
 */
internal class DesktopTextInputController(
    private val overlayPanel: Container,
    private val koolCanvas: Component,
    private val sendKey: (KeyCode, Int) -> Unit = ::dispatchKoolKey,
    private val dispatch: (() -> Unit) -> Unit = { action -> FrontendScope.launch { action() } },
) : PlatformTextInputController {

    private val editor = JTextField()
    private val maxLengthFilter = MaxLengthFilter()

    /** Owner of the Kool field that currently wants text input, null when nothing is edited. */
    @Volatile
    private var activeRequest: PlatformTextInputRequest? = null

    private var suppressTextCallback = false
    private var lastForwardedText = ""
    private var focusFailureLogged = false

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
        overlayPanel.add(editor)
    }

    override fun showOrUpdate(request: PlatformTextInputRequest) {
        val previous = activeRequest
        val isNewOwner = previous?.owner !== request.owner
        activeRequest = request
        runOnEdt {
            if (activeRequest?.owner !== request.owner) return@runOnEdt
            maxLengthFilter.maxLength = request.maxLength.coerceAtLeast(1)
            if (isNewOwner) {
                lastForwardedText = request.text
                replaceEditorText(request.text)
                positionEditorNearPointer()
            } else if (request.text != lastForwardedText) {
                // The Kool side rewrote the text (send-and-clear, filtering, ...): mirror it.
                lastForwardedText = request.text
                replaceEditorText(request.text)
            }
            if (!editor.isFocusOwner) {
                if (editor.requestFocusInWindow()) {
                    focusFailureLogged = false
                } else if (!focusFailureLogged) {
                    focusFailureLogged = true
                    logger.debug("Desktop text input editor could not take focus; IME stays unavailable")
                }
            }
        }
    }

    override fun hide(owner: Any) {
        if (activeRequest?.owner !== owner) return
        activeRequest = null
        runOnEdt { returnFocusToKoolCanvas() }
    }

    override fun dismissKeyboard() {
        activeRequest = null
        runOnEdt { returnFocusToKoolCanvas() }
    }

    fun dispose() {
        activeRequest = null
        runOnEdt { overlayPanel.remove(editor) }
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
                // Let the Kool UI unfocus the field (and close popups) with its normal escape path.
                sendKey(KeyboardInput.KEY_ESC, 0)
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

    private fun replaceEditorText(text: String) {
        if (editor.text == text) return
        suppressTextCallback = true
        try {
            editor.text = text
            editor.caretPosition = editor.text.length
        } finally {
            suppressTextCallback = false
        }
    }

    private fun returnFocusToKoolCanvas() {
        if (editor.isFocusOwner) {
            koolCanvas.requestFocusInWindow()
        }
    }

    /** Puts the editor where the user clicked, so the IME candidate window shows up near the field. */
    private fun positionEditorNearPointer() {
        val location = runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull() ?: return
        runCatching {
            SwingUtilities.convertPointFromScreen(location, overlayPanel)
            val maxX = (overlayPanel.width - EDITOR_SIZE_PX).coerceAtLeast(0)
            val maxY = (overlayPanel.height - EDITOR_SIZE_PX).coerceAtLeast(0)
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
