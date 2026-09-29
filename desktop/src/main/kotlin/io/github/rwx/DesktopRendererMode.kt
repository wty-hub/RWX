package io.github.rwx

import io.github.rwx.render.RendererMode

internal enum class DesktopRendererMode(
    override val id: String,
) : RendererMode{
    Slick(
        "desktop-slick"
    ),

    /**
     * Renders the game world through the Kool canvas (Vulkan, i.e. MoltenVK/Metal on macOS)
     * instead of the AWT OpenGL canvas. No GL context and no per-frame framebuffer readback.
     */
    Kool(
        "desktop-kool"
    ),
}