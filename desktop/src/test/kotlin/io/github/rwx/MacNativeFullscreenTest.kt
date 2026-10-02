package io.github.rwx

import java.awt.Canvas
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacNativeFullscreenTest {
    @Test
    fun `macOS JDK delivers native enter and exit callbacks with a decorated frame`() {
        if (!System.getProperty("os.name").startsWith("mac", true) || GraphicsEnvironment.isHeadless()) return
        var frame: JFrame? = null
        var controller: MacNativeFullscreen? = null
        val entered = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val events = java.util.concurrent.CopyOnWriteArrayList<Boolean>()
        var fullscreen = false
        try {
            SwingUtilities.invokeAndWait {
                frame = JFrame("RWX native fullscreen test").apply {
                    add(Canvas().apply { preferredSize = Dimension(800, 600) })
                    controller = MacNativeFullscreen(this,
                        onTransitionStarted = { events.add(it); fullscreen = it },
                        onTransitionCompleted = { if (fullscreen) entered.countDown() else exited.countDown() },
                    )
                    pack()
                    setLocationRelativeTo(null)
                    isVisible = true
                }
                assertEquals(false, frame!!.isUndecorated)
                assertEquals(true, frame!!.rootPane.getClientProperty("apple.awt.fullscreenable"))
                controller!!.request(true)
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Native fullscreen enter callback missing")
            SwingUtilities.invokeAndWait { controller!!.request(false) }
            assertTrue(exited.await(10, TimeUnit.SECONDS), "Native fullscreen exit callback missing")
            assertEquals(listOf(true, false), events)
        } finally {
            SwingUtilities.invokeAndWait {
                controller?.close()
                frame?.dispose()
            }
        }
    }
}
