package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameSession
import kotlin.test.Test
import kotlin.test.assertEquals

class KoolInputFocusResetTest {
    @Test
    fun `focus loss releases forwarded keys and ignores stale repeats`() {
        val session = RecordingGameSession()
        val sink = LegacyGameKeyboardSink(session)

        sink.forwardAndroidKey(29, isPressed = true)
        sink.forwardAndroidKey(29, isRepeated = true)
        sink.resetOnHostFocusLost()
        sink.forwardAndroidKey(29, isRepeated = true)
        assertEquals(listOf(29 to true, 29 to true, 29 to false), session.keys)

        sink.forwardAndroidKey(29, isPressed = true)
        sink.forwardAndroidKey(29, isReleased = true)
        assertEquals(
            listOf(29 to true, 29 to true, 29 to false, 29 to true, 29 to false),
            session.keys,
        )
    }

    private class RecordingGameSession : GameSession() {
        val keys = mutableListOf<Pair<Int, Boolean>>()

        override val rendererMode: RendererMode = object : RendererMode { override val id = "test" }

        override fun submitKey(androidKeyCode: Int, isDown: Boolean) {
            keys += androidKeyCode to isDown
        }

        override fun loadPendingMapNow(): KoolCanvasFrame =
            KoolCanvasFrame(KoolCanvasViewport(1, 1), emptyList())

        override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine =
            error("not needed for input tests")

        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
    }
}
