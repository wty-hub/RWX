package io.github.rwx

import de.fabmax.kool.input.KeyCode
import de.fabmax.kool.input.KeyboardInput
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
            val overlayPanel = JPanel(null)
            val koolCanvas = java.awt.Canvas()
            val controller = DesktopTextInputController(
                overlayPanel = overlayPanel,
                koolCanvas = koolCanvas,
                sendKey = sendKey,
                dispatch = { action -> action() },
            )
            try {
                val editor = overlayPanel.components.filterIsInstance<JTextField>().single()
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
        onChange: (String) -> Unit = {},
    ) = PlatformTextInputRequest(
        owner = owner,
        text = text,
        hint = "",
        maxLength = maxLength,
        onChange = onChange,
        onEnter = onEnter,
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
