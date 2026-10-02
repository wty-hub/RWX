package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import de.fabmax.kool.input.InputStack
import de.fabmax.kool.input.KeyEvent
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.LocalKeyCode
import de.fabmax.kool.input.UniversalKeyCode
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertFalse

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

    @Test
    fun `battle capture stays above a focused HUD and releases keys when a dialog opens`() {
        val session = RecordingGameSession()
        val sink = LegacyGameKeyboardSink(session)
        var enabled = true
        val capture = GameKeyboardInputHandler({ enabled }, sink)
        val hud = InputStack.InputHandler("focused-hud").apply { blockAllKeyboardInput = true }
        try {
            InputStack.pushTop(hud)
            capture.syncRegistration()
            capture.syncRegistration()
            InputStack.updateHandlerStack()
            assertSame(capture, InputStack.handlerStack.last())
            assertEquals(1, InputStack.handlerStack.count { it === capture })
            sink.forwardAndroidKey(29, isPressed = true)

            enabled = false
            capture.syncRegistration()
            InputStack.updateHandlerStack()
            assertFalse(capture in InputStack.handlerStack)
            assertEquals(listOf(29 to true, 29 to false), session.keys)

            enabled = true
            capture.syncRegistration()
            InputStack.updateHandlerStack()
            assertSame(capture, InputStack.handlerStack.last())
        } finally {
            enabled = false
            capture.syncRegistration()
            InputStack.handlerStack.stageRemove(hud)
            InputStack.updateHandlerStack()
        }
    }

    @Test
    fun `navigation consumed keys and typed characters do not become engine shortcuts`() {
        val session = RecordingGameSession()
        val sink = LegacyGameKeyboardSink(session)
        val escape = KeyEvent(KeyboardInput.KEY_ESC, LocalKeyCode(-9), KeyboardInput.KEY_EV_DOWN, 0)
            .apply { isConsumed = true }
        val typed = KeyEvent(UniversalKeyCode(65), LocalKeyCode(65), KeyboardInput.KEY_EV_CHAR_TYPED, 0)
        val down = KeyEvent(UniversalKeyCode(65), LocalKeyCode(65), KeyboardInput.KEY_EV_DOWN, 0)
        val up = KeyEvent(UniversalKeyCode(65), LocalKeyCode(65), KeyboardInput.KEY_EV_UP, 0)
        sink.forwardKeyEvents(listOf(escape, typed, down, up))
        assertEquals(listOf(29 to true, 29 to false), session.keys)
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
