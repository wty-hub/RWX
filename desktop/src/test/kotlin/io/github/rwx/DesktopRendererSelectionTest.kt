package io.github.rwx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DesktopRendererSelectionTest {
    @Test
    fun `macOS defaults to the Kool Vulkan renderer and other desktops keep Slick`() {
        assertEquals(DesktopRendererKind.Kool, DesktopRendererSelection.defaultFor("Mac OS X"))
        assertEquals(DesktopRendererKind.Kool, DesktopRendererSelection.defaultFor("macOS"))
        assertEquals(DesktopRendererKind.Slick, DesktopRendererSelection.defaultFor("Linux"))
        assertEquals(DesktopRendererKind.Slick, DesktopRendererSelection.defaultFor("Windows 11"))
        assertEquals(DesktopRendererKind.Kool, DesktopRendererSelection.resolve(null, "Mac OS X"))
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
