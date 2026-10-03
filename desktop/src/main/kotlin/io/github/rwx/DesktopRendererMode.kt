package io.github.rwx

import io.github.rwx.render.RendererMode

internal enum class DesktopRendererMode(
    override val id: String,
) : RendererMode {
    /**
     * Renders the game world through the Kool canvas: Vulkan by default on Windows and macOS
     * (MoltenVK/Metal), OpenGL by default on Linux. The legacy Slick canvas and per-frame
     * framebuffer readback have been removed.
     */
    Kool("desktop-kool"),
}
