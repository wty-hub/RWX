package io.github.rwx.slick

import com.corrodinggames.rts.gameFramework.utility.SlickToAndroidKeycodes
import java.awt.Canvas
import java.awt.event.KeyEvent
import org.newdawn.slick.Input
import kotlin.test.Test
import kotlin.test.assertEquals

class SlickCanvasHostTest {
    @Test
    fun `canvas Enter and T use the legacy chat shortcut key codes`() {
        val canvas = Canvas()
        fun slickKey(awtKey: Int): Int = KeyEvent(
            canvas, KeyEvent.KEY_PRESSED, 0L, 0, awtKey, KeyEvent.CHAR_UNDEFINED,
        ).toSlickKey()

        assertEquals(Input.KEY_ENTER, slickKey(KeyEvent.VK_ENTER))
        assertEquals(66, SlickToAndroidKeycodes.convertSlickToAndroidKeyCode(slickKey(KeyEvent.VK_ENTER)))
        assertEquals(Input.KEY_T, slickKey(KeyEvent.VK_T))
        assertEquals(48, SlickToAndroidKeycodes.convertSlickToAndroidKeyCode(slickKey(KeyEvent.VK_T)))
    }

    @Test
    fun `host focus loss reaches the registered game handler`() {
        var focusLosses = 0
        SlickCanvasHost.setHostFocusLostHandler { focusLosses++ }
        try {
            SlickCanvasHost.notifyHostFocusLost()
            assertEquals(1, focusLosses)
        } finally {
            SlickCanvasHost.setHostFocusLostHandler(null)
        }

        SlickCanvasHost.notifyHostFocusLost()
        assertEquals(1, focusLosses)
    }
}
