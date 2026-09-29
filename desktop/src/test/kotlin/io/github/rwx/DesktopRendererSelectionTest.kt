package io.github.rwx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopRendererSelectionTest {
    @Test
    fun `defaults to the Slick canvas renderer until the Kool gate passes`() {
        assertEquals(DesktopRendererKind.Slick, DesktopRendererSelection.defaultFor("Mac OS X"))
        assertEquals(DesktopRendererKind.Slick, DesktopRendererSelection.defaultFor("Linux"))
        assertEquals(DesktopRendererKind.Slick, DesktopRendererSelection.resolve(null, "Mac OS X"))
        assertEquals(DesktopRendererKind.Slick, DesktopRendererSelection.resolve("", "Linux"))
    }

    @Test
    fun `explicit selection wins and ignores case and padding`() {
        assertEquals(DesktopRendererKind.Kool, DesktopRendererSelection.resolve("kool", "Linux"))
        assertEquals(DesktopRendererKind.Kool, DesktopRendererSelection.resolve("KOOL", "Mac OS X"))
        assertEquals(DesktopRendererKind.Slick, DesktopRendererSelection.resolve(" slick ", "Mac OS X"))
    }

    @Test
    fun `unknown renderer names fail fast instead of silently falling back`() {
        assertFailsWith<IllegalArgumentException> {
            DesktopRendererSelection.resolve("metal", "Mac OS X")
        }
    }
}
