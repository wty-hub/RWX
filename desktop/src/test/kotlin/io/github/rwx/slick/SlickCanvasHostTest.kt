package io.github.rwx.slick

import kotlin.test.Test
import kotlin.test.assertEquals

class SlickCanvasHostTest {
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
