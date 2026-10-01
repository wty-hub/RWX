package io.github.rwx

import com.corrodinggames.rts.gameFramework.GameEngine
import org.newdawn.slick.GameContainer
import org.newdawn.slick.opengl.renderer.Renderer
import java.awt.Canvas
import java.io.File
import javax.swing.JFrame
import javax.swing.Timer

/**
 * TEMPORARY diagnostic used while investigating the "window grow displaces the HUD" report.
 * Remove this file (and its call sites) before committing.
 */
internal object DebugResizeProbe {
    private const val PROPERTY = "rwx.debug.resize"
    private val logFile = File(System.getProperty("rwx.debug.log", "/tmp/rwx-resize-debug.log"))

    fun log(message: String) {
        runCatching { logFile.appendText(message + "\n") }
    }

    /** `1900x1040@4000` from -Drwx.debug.resize or /tmp/rwx-resize-spec.txt grows the window. */
    fun scheduleResize(frame: JFrame) {
        val spec = System.getProperty(PROPERTY)?.takeIf { it.isNotBlank() }
            ?: File("/tmp/rwx-resize-spec.txt").takeIf { it.isFile }?.readText()?.trim()
            ?: return
        val size = spec.substringBefore('@').split('x')
        val delayMillis = spec.substringAfter('@', "4000").toIntOrNull() ?: 4000
        val width = size[0].toInt()
        val height = size.getOrNull(1)?.toInt() ?: 1040
        frame.isAlwaysOnTop = true
        frame.toFront()
        Timer(delayMillis) {
            log("=== debug resize to ${width}x$height requested")
            frame.setSize(width, height)
        }.apply {
            isRepeats = false
            start()
        }
    }

    fun logFrameState(container: GameContainer, canvas: Canvas, framebufferWidth: Int, framebufferHeight: Int) {
        val engine = GameEngine.getInstance()
        val minimap = engine?.minimap
        val renderer = runCatching {
            val rendererInstance = Renderer.get()
            val widthField = rendererInstance::class.java.getDeclaredField("width")
            widthField.isAccessible = true
            val heightField = rendererInstance::class.java.getDeclaredField("height")
            heightField.isAccessible = true
            "${widthField.get(rendererInstance)}x${heightField.get(rendererInstance)}"
        }.getOrElse { "?" }
        val minimapTexture = runCatching {
            val texture = minimap?.unitsTexture ?: return@runCatching "none"
            "${texture.width()}x${texture.height()}"
        }.getOrElse { "?" }
        log(
            "resize canvas=${canvas.width}x${canvas.height} container=${container.width}x${container.height} " +
                "framebuffer=${framebufferWidth}x$framebufferHeight rendererOrtho=$renderer " +
                "engine=${engine?.screenWidth}x${engine?.screenHeight} scale=${engine?.screenScale} " +
                "sidebar=${engine?.sidebarWidth} minimapBounds=${minimap?.minimapBoundsRect} " +
                "minimapTexture=$minimapTexture",
        )
        runCatching {
            val projection = org.lwjglx.opengl.GL11.glGetFloat(org.lwjglx.opengl.GL11.GL_PROJECTION_MATRIX)
            log("   projScale=($projection)")
        }.onFailure { log("   gl state read failed: $it") }
    }
}
