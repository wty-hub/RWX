package io.github.rwx

import de.fabmax.kool.pipeline.backend.gl.RenderBackendGl
import de.fabmax.kool.pipeline.backend.vk.RenderBackendVk
import kotlin.test.Test
import kotlin.test.assertSame

class DesktopRenderBackendTest {
    @Test
    fun `macOS defaults to Vulkan to avoid the AWT OpenGL path`() {
        assertSame(RenderBackendVk.Companion, KoolDesktopMain.resolveDesktopRenderBackend(null, "Mac OS X"))
        assertSame(RenderBackendVk.Companion, KoolDesktopMain.resolveDesktopRenderBackend("", "macOS"))
    }

    @Test
    fun `Windows defaults to Vulkan`() {
        assertSame(RenderBackendVk.Companion, KoolDesktopMain.resolveDesktopRenderBackend(null, "Windows 11"))
        assertSame(RenderBackendVk.Companion, KoolDesktopMain.resolveDesktopRenderBackend("", "Windows 10"))
    }

    @Test
    fun `Linux keeps OpenGL and explicit selection still wins`() {
        assertSame(RenderBackendGl.Companion, KoolDesktopMain.resolveDesktopRenderBackend(null, "Linux"))
        assertSame(RenderBackendGl.Companion, KoolDesktopMain.resolveDesktopRenderBackend("opengl", "Mac OS X"))
        assertSame(RenderBackendGl.Companion, KoolDesktopMain.resolveDesktopRenderBackend("gl", "Windows 11"))
        assertSame(RenderBackendVk.Companion, KoolDesktopMain.resolveDesktopRenderBackend("vulkan", "Windows 11"))
    }
}
