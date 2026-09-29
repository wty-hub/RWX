package io.github.rwx

/**
 * Which desktop game renderer drives the session.
 *
 * [Slick] is the AWT/OpenGL canvas (legacy GL 2.1 on macOS, with a per-frame framebuffer
 * readback). [Kool] draws the game through the Kool canvas command stream, which the Kool
 * render backend presents: Vulkan on every supported desktop, so MoltenVK/Metal on macOS.
 */
internal enum class DesktopRendererKind {
    Slick,
    Kool,
}

internal object DesktopRendererSelection {
    const val PROPERTY: String = "rwx.desktop.renderer"
    const val ENV: String = "RWX_DESKTOP_RENDERER"

    fun requestedValue(): String? =
        System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }
            ?: System.getenv(ENV)?.takeIf { it.isNotBlank() }

    /**
     * Default renderer per OS. macOS has no usable game OpenGL path (Apple's GL is a deprecated
     * 2.1 shim and the Slick host has to read every frame back), so it presents through the Kool
     * renderer — Vulkan, i.e. MoltenVK/Metal. Linux and Windows keep the Slick canvas.
     */
    fun defaultFor(osName: String = System.getProperty("os.name")): DesktopRendererKind =
        if (osName.lowercase().startsWith("mac")) {
            DesktopRendererKind.Kool
        } else {
            DesktopRendererKind.Slick
        }

    fun resolve(
        requested: String? = requestedValue(),
        osName: String = System.getProperty("os.name"),
    ): DesktopRendererKind = when (requested?.trim()?.lowercase()) {
        null, "" -> defaultFor(osName)
        "slick" -> DesktopRendererKind.Slick
        "kool" -> DesktopRendererKind.Kool
        else -> throw IllegalArgumentException(
            "Unsupported desktop renderer '$requested'. Supported values: slick, kool.",
        )
    }
}
