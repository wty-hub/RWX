package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import de.fabmax.kool.input.Pointer
import de.fabmax.kool.math.MutableVec2f
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameCameraSnapshot
import io.github.rwx.session.GamePointerFrameContext
import io.github.rwx.session.GameSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class KoolPointerFrameCaptureTest {
    @Test fun `pointer and focus release retain one captured camera and resized surface`() {
        val session = RecordingSession()
        val seen = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 100f, 200f, 2f)
        session.camera = seen
        var surface = KoolCanvasViewport(1920, 900)
        var viewportReads = 0
        val sink = LegacyGamePointerSink(session, viewportProvider = { viewportReads++; surface })
        sink.onPointer(pointer(1200f, 625f, true))
        val press = session.pointers.single()
        assertEquals(1, session.cameraReads)
        assertEquals(1, viewportReads)
        assertSame(seen, press.context.camera)
        assertEquals(surface, press.context.surfaceViewport)
        assertEquals(800f to 500f, press.context.positionInSeenViewport(press.x, press.y))

        session.camera = seen.copy(revision = 99, zoom = 0.5f)
        surface = KoolCanvasViewport(3200, 1800)
        sink.resetOnHostFocusLost()
        val release = session.pointers.last()
        assertEquals(false, release.down)
        assertSame(press.context, release.context)
        assertEquals(1, session.cameraReads)
        assertEquals(1, viewportReads)
    }

    @Test fun `logical pointer conversion scales the captured surface in the same units`() {
        val session = RecordingSession()
        session.camera = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 0f, 0f, 1f)
        val sink = LegacyGamePointerSink(session, KoolPointerScaleProvider { 0.5f },
            viewportProvider = { KoolCanvasViewport(2560, 1440) })
        sink.onPointer(pointer(1600f, 1000f, true))
        val press = session.pointers.single()
        assertEquals(800f to 500f, press.x to press.y)
        assertEquals(KoolCanvasViewport(1280, 720), press.context.surfaceViewport)
        assertEquals(800f to 500f, press.context.positionInSeenViewport(press.x, press.y))
    }

    private fun pointer(x: Float, y: Float, down: Boolean) = Pointer().apply {
        (pos as MutableVec2f).set(x, y)
        // Kool's public Pointer is read-only input; populate its producer-owned fixture fields.
        Pointer::class.java.getDeclaredField("isValid").apply { isAccessible = true }.setBoolean(this, true)
        Pointer::class.java.getDeclaredField("buttonMask").apply { isAccessible = true }.setInt(this, if (down) 1 else 0)
    }

    private data class CapturedPointer(val x: Float, val y: Float, val down: Boolean, val context: GamePointerFrameContext)
    private class RecordingSession : GameSession() {
        var camera: GameCameraSnapshot? = null
        var cameraReads = 0
        val pointers = mutableListOf<CapturedPointer>()
        override val rendererMode: RendererMode = object : RendererMode { override val id = "test" }
        override fun cameraSnapshot(): GameCameraSnapshot? { cameraReads++; return camera }
        override fun submitPointer(screenX: Float, screenY: Float, isDown: Boolean, pointerId: Int,
            frameContext: GamePointerFrameContext) { pointers += CapturedPointer(screenX, screenY, isDown, frameContext) }
        override fun loadPendingMapNow() = KoolCanvasFrame(KoolCanvasViewport(1, 1), emptyList())
        override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine = error("Input test does not create an engine")
        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
    }
}
