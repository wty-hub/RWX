package io.github.rwx

import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopRenderBackendTest {
    @Test
    fun `macOS defaults to Vulkan to avoid the AWT OpenGL path`() {
        assertSame(RenderBackendVk.Companion, KoolDesktopMain.resolveDesktopRenderBackend(null, "Mac OS X"))
        assertSame(RenderBackendVk.Companion, KoolDesktopMain.resolveDesktopRenderBackend("", "macOS"))
    }

    @Test
    fun `other desktops keep OpenGL and explicit selection still wins`() {
        assertSame(RenderBackendGl.Companion, KoolDesktopMain.resolveDesktopRenderBackend(null, "Linux"))
        assertSame(RenderBackendGl.Companion, KoolDesktopMain.resolveDesktopRenderBackend("opengl", "Mac OS X"))
        assertSame(RenderBackendVk.Companion, KoolDesktopMain.resolveDesktopRenderBackend("vulkan", "Windows 11"))
    }

    @Test
    fun `macOS starts windowed even when fullscreen was saved`() {
        assertFalse(KoolDesktopMain.desktopStartupFullscreen(true, "Mac OS X"))
        assertTrue(KoolDesktopMain.desktopStartupFullscreen(true, "Linux"))
        assertFalse(KoolDesktopMain.desktopStartupFullscreen(false, "Linux"))
    }

    @Test
    fun `only macOS uses the single window capture host`() {
        assertTrue(KoolDesktopMain.desktopSingleWindowCapture("Mac OS X", null))
        assertTrue(KoolDesktopMain.desktopSingleWindowCapture("macOS", null))
        assertFalse(KoolDesktopMain.desktopSingleWindowCapture("Windows 11", null))
        assertFalse(KoolDesktopMain.desktopSingleWindowCapture("Linux", null))
        assertFalse(KoolDesktopMain.desktopSingleWindowCapture("Mac OS X", "false"))
        assertTrue(KoolDesktopMain.desktopSingleWindowCapture("Mac OS X", "true"))
    }
}
