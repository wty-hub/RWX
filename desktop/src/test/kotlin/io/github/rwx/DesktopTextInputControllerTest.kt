package io.github.rwx

import de.fabmax.kool.input.KeyCode
import de.fabmax.kool.input.KeyboardInput
import io.github.rwx.ui.component.PlatformCaretRequest
import io.github.rwx.ui.component.PlatformTextInputRequest
import java.awt.GraphicsEnvironment
import java.awt.event.KeyEvent
import java.text.AttributedString
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopTextInputControllerTest {

    @Test
    fun `typing forwards committed text and external rewrites do not echo back`() {
        withController { controller, editor ->
            val owner = Any()
            val received = mutableListOf<String>()
            controller.showOrUpdate(request(owner, "abc") { received += it })
            assertEquals("abc", editor.text)

            editor.document.insertString(3, "d", null)
            assertEquals("abcd", editor.text)
            assertEquals(listOf("abcd"), received)

            // The Kool side rewrote the value (send-and-clear, filtering, ...): identical to the
            // editor? then the editor is updated without echoing the change back.
            controller.showOrUpdate(request(owner, "") { received += it })
            assertEquals("", editor.text)
            assertEquals(listOf("abcd"), received)
        }
    }

    @Test
    fun `caret request moves the editor caret and selection once`() {
        withController { controller, editor ->
            val owner = Any()
            controller.showOrUpdate(request(owner, "abcdef"))

            controller.showOrUpdate(
                request(owner, "abcdef", caretRequest = PlatformCaretRequest(1, selectionStart = 1, caret = 1))
            )
            assertEquals(1, editor.caretPosition)

            // Inserting now must happen in the middle instead of appending at the end.
            editor.document.insertString(editor.caretPosition, "X", null)
            assertEquals("aXbcdef", editor.text)

            controller.showOrUpdate(
                request(
                    owner,
                    "aXbcdef",
                    caretRequest = PlatformCaretRequest(2, selectionStart = 0, caret = 3),
                )
            )
            assertEquals(3, editor.caretPosition)
            assertEquals(0, editor.selectionStart)

            // The same request must not be applied twice (it would fight the user's own edits).
            controller.showOrUpdate(
                request(
                    owner,
                    "aXbcdef",
                    caretRequest = PlatformCaretRequest(2, selectionStart = 5, caret = 5),
                )
            )
            assertEquals(3, editor.caretPosition)
        }
    }

    @Test
    fun `editor selection changes are reported back`() {
        withController { controller, editor ->
            val reported = mutableListOf<Pair<Int, Int>>()
            controller.showOrUpdate(
                request(Any(), "hello", onSelectionChanged = { selectionStart, caret ->
                    reported += selectionStart to caret
                })
            )
            reported.clear()

            editor.document.insertString(5, "!", null)
            editor.caretPosition = 2

            assertTrue(reported.isNotEmpty(), "expected a selection report after editing")
            assertEquals(2 to 2, reported.last())
        }
    }

    @Test
    fun `mirroring an external text change keeps the caret in place`() {
        withController { controller, editor ->
            val owner = Any()
            controller.showOrUpdate(request(owner, "abcdef"))
            editor.caretPosition = 2

            controller.showOrUpdate(request(owner, "abc"))

            assertEquals("abc", editor.text)
            assertEquals(2, editor.caretPosition)
        }
    }

    @Test
    fun `in-progress composition stays out of the kool field`() {
        withController { controller, editor ->
            val received = mutableListOf<String>()
            controller.showOrUpdate(request(Any(), "ab") { received += it })

            // Swing keeps marked (composing) text in the document with the composed-text attribute.
            val composed = SimpleAttributeSet()
            composed.addAttribute(StyleConstants.ComposedTextAttribute, AttributedString("nihao"))
            editor.document.insertString(2, "nihao", composed)
            assertEquals("abnihao", editor.text)
            assertEquals(emptyList<String>(), received)

            // Committing the composition replaces the marked run with the real characters.
            editor.document.remove(2, 5)
            editor.document.insertString(2, "你好", null)
            assertEquals(listOf("ab你好"), received)
        }
    }

    @Test
    fun `editor text is capped at the requested max length`() {
        withController { controller, editor ->
            val received = mutableListOf<String>()
            controller.showOrUpdate(request(Any(), "", maxLength = 4) { received += it })

            editor.document.insertString(0, "abcdefgh", null)

            assertEquals("abcd", editor.text)
            assertEquals(listOf("abcd"), received)
        }
    }

    @Test
    fun `enter invokes the enter handler with the committed text`() {
        withController { controller, editor ->
            val entered = mutableListOf<String>()
            controller.showOrUpdate(request(Any(), "hello", onEnter = { entered += it }))

            pressKey(editor, KeyEvent.VK_ENTER)

            assertEquals(listOf("hello"), entered)
        }
    }

    @Test
    fun `escape is forwarded to the kool input stack`() {
        val keys = mutableListOf<Pair<KeyCode, Int>>()
        withController(sendKey = { code, modifiers -> keys += code to modifiers }) { controller, editor ->
            controller.showOrUpdate(request(Any(), ""))

            pressKey(editor, KeyEvent.VK_ESCAPE)

            assertEquals(listOf<Pair<KeyCode, Int>>(KeyboardInput.KEY_ESC to 0), keys)
        }
    }

    @Test
    fun `hide stops forwarding further edits`() {
        withController { controller, editor ->
            val owner = Any()
            val received = mutableListOf<String>()
            controller.showOrUpdate(request(owner, "") { received += it })

            controller.hide(owner)
            editor.document.insertString(0, "x", null)

            assertEquals(emptyList<String>(), received)
        }
    }

    @Test
    fun `tab is forwarded with the shift modifier`() {
        val keys = mutableListOf<Pair<KeyCode, Int>>()
        withController(sendKey = { code, modifiers -> keys += code to modifiers }) { controller, editor ->
            controller.showOrUpdate(request(Any(), ""))

            pressKey(editor, KeyEvent.VK_TAB, shift = true)

            assertEquals(listOf<Pair<KeyCode, Int>>(KeyboardInput.KEY_TAB to 1), keys)
        }
    }

    private fun withController(
        sendKey: (KeyCode, Int) -> Unit = { _, _ -> },
        block: (DesktopTextInputController, JTextField) -> Unit,
    ) {
        if (GraphicsEnvironment.isHeadless()) return
        SwingUtilities.invokeAndWait {
            val editorHost = JPanel(null)
            val controller = DesktopTextInputController(
                editorHost = editorHost,
                activateForEditing = {},
                restoreFocus = {},
                sendKey = sendKey,
                dispatch = { action -> action() },
            )
            try {
                val editor = editorHost.components.filterIsInstance<JTextField>().single()
                block(controller, editor)
            } finally {
                controller.dispose()
            }
        }
    }

    private fun request(
        owner: Any,
        text: String,
        maxLength: Int = 100,
        onEnter: ((String) -> Unit)? = null,
        caretRequest: PlatformCaretRequest? = null,
        onSelectionChanged: ((Int, Int) -> Unit)? = null,
        onChange: (String) -> Unit = {},
    ) = PlatformTextInputRequest(
        owner = owner,
        text = text,
        hint = "",
        maxLength = maxLength,
        onChange = onChange,
        onEnter = onEnter,
        caretRequest = caretRequest,
        onSelectionChanged = onSelectionChanged,
    )

    private fun pressKey(editor: JTextField, keyCode: Int, shift: Boolean = false) {
        val modifiers = if (shift) KeyEvent.SHIFT_DOWN_MASK else 0
        val event = KeyEvent(
            editor,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            modifiers,
            keyCode,
            KeyEvent.CHAR_UNDEFINED,
        )
        val listeners = editor.keyListeners
        assertTrue(listeners.isNotEmpty(), "the platform editor has no key listener")
        listeners.forEach { it.keyPressed(event) }
    }
}
