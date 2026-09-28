package io.github.rwx.slick

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.EXTFramebufferObject
import org.lwjgl.opengl.awt.GLData
import org.newdawn.slick.Graphics
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacOffscreenPlatformCanvasTest {
    @Test
    fun `main Graphics scales full and partial scissor into Retina framebuffer`() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ||
            GraphicsEnvironment.isHeadless()
        ) return

        val canvas = SlickAwtGLCanvas(GLData(), offscreenOnMac = true)
        val frame = JFrame("RWX Retina scissor test")
        try {
            SwingUtilities.invokeAndWait {
                frame.setSize(160, 120)
                frame.contentPane.layout = null
                frame.add(canvas)
                frame.isVisible = true
                val transform = canvas.graphicsConfiguration.defaultTransform
                canvas.setBounds(
                    0, 0,
                    (64.0 / transform.scaleX).roundToInt(),
                    (48.0 / transform.scaleY).roundToInt(),
                )
            }
            canvas.runInContext {
                GL.createCapabilities()
                assertEquals(java.awt.Dimension(64, 48), canvas.offscreenFramebufferSize())
                val graphics = Graphics(32, 24)
                graphics.setFramebufferScale(2f, 2f)
                graphics.setClip(0, 0, 32, 24)
                assertScissorBox(0, 0, 64, 48)

                graphics.setClip(4, 3, 10, 7)
                assertScissorBox(8, 28, 20, 14)

                // A window move can change the display scale without changing the logical clip.
                graphics.setFramebufferScale(1f, 1f)
                assertScissorBox(4, 14, 10, 7)
                graphics.setFramebufferScale(2f, 2f)
                assertScissorBox(8, 28, 20, 14)
                graphics.clearClip()
                graphics.flush()
            }
        } finally {
            canvas.disposeCanvas()
            GL.setCapabilities(null)
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    @Test
    fun `CGL framebuffer stays readable across resize without a native OpenGL view`() {
        if (!System.getProperty("os.name").startsWith("Mac", ignoreCase = true) ||
            GraphicsEnvironment.isHeadless()
        ) return

        val canvas = SlickAwtGLCanvas(
            GLData().apply { depthSize = 24; stencilSize = 8 },
            offscreenOnMac = true,
        )
        val frame = JFrame("RWX offscreen GL test")
        try {
            SwingUtilities.invokeAndWait {
                frame.setSize(160, 120)
                frame.add(canvas)
                frame.isVisible = true
                canvas.setSize(32, 24)
            }
            canvas.runInContext {
                GL.createCapabilities()
                assertReadableFramebuffer()
            }
            SwingUtilities.invokeAndWait { canvas.setSize(64, 48) }
            canvas.runInContext { assertReadableFramebuffer() }
        } finally {
            canvas.disposeCanvas()
            GL.setCapabilities(null)
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    private fun assertReadableFramebuffer() {
        assertTrue(GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT) != 0)
        assertEquals(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.glGetInteger(GL11.GL_DRAW_BUFFER))
        assertEquals(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT, GL11.glGetInteger(GL11.GL_READ_BUFFER))
        GL11.glClearColor(0.25f, 0.5f, 0.75f, 1f)
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT)
        val pixel = BufferUtils.createByteBuffer(4)
        GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel)
        assertEquals(GL11.GL_NO_ERROR, GL11.glGetError())
        assertTrue((pixel.get(0).toInt() and 0xff) in 60..68)
        assertTrue((pixel.get(1).toInt() and 0xff) in 124..132)
        assertTrue((pixel.get(2).toInt() and 0xff) in 187..195)
    }

    private fun assertScissorBox(x: Int, y: Int, width: Int, height: Int) {
        assertTrue(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST))
        val box = BufferUtils.createIntBuffer(4)
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, box)
        assertEquals(listOf(x, y, width, height), (0..3).map(box::get))
    }
}
