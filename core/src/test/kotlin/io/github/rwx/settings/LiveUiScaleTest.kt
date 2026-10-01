package io.github.rwx.settings

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.SettingsEngine
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.render.canvas.KoolPaint
import io.github.rwx.session.GameSession
import io.github.rwx.ui.model.SettingsModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LiveUiScaleTest {
    @Test
    fun `preview updates paused HUD and fonts without advancing the game or changing the surface`() = withEngine { engine ->
        val storage = MemoryPreferenceStorage()
        val repository = GameSettingsRepository(storage)
        val session = TestSession(engine)
        val model = SettingsModel()
        engine.isPaused = true
        engine.isStopped = true
        engine.updateWindowResolution(1280, 720, 2f)
        val paint = KoolPaint()
        engine.updatePaintTextSize(paint, 16f)
        val centerX = engine.viewpointX + engine.halfVisibleWorldWidth
        val centerY = engine.viewpointY + engine.halfVisibleWorldHeight
        val tick = engine.currentTick

        model.uiScale.value = 1.5f
        repository.applyLive(model)
        session.applyDisplaySettings()

        assertEquals(1f, engine.screenScale, "Layout must wait for the engine's owner thread")
        session.drainCommands()
        assertEquals(1.5f, engine.screenScale)
        assertEquals(24f, paint.k())
        assertEquals(1280f, engine.screenWidth)
        assertEquals(720f, engine.screenHeight)
        assertEquals(2f, engine.renderSurfaceScale)
        assertEquals(centerX, engine.viewpointX + engine.halfVisibleWorldWidth)
        assertEquals(centerY, engine.viewpointY + engine.halfVisibleWorldHeight)
        assertEquals(tick, engine.currentTick)
        assertFalse(storage.preference("preferences").contains("uiRenderScale"))
    }

    @Test
    fun `queued previews apply the latest scale in both directions`() = withEngine { engine ->
        val repository = GameSettingsRepository(MemoryPreferenceStorage())
        val session = TestSession(engine)
        val model = SettingsModel()
        engine.updateWindowResolution(1280, 720)
        val paint = KoolPaint()
        engine.updatePaintTextSize(paint, 16f)

        for (scale in listOf(1.25f, 1.5f, 2f)) {
            model.uiScale.value = scale
            repository.applyLive(model)
            session.applyDisplaySettings()
        }
        session.drainCommands()
        assertEquals(2f, engine.screenScale)
        assertEquals(32f, paint.k())

        model.uiScale.value = 0.75f
        repository.applyLive(model)
        session.applyDisplaySettings()
        session.drainCommands()
        assertEquals(0.75f, engine.screenScale)
        assertEquals(12f, paint.k())
    }

    private fun withEngine(block: (GameLogic) -> Unit) {
        val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instanceField.get(null)
        instanceField.set(null, null)
        try {
            val engine = GameLogic()
            engine.settingsEngine = SettingsEngine::class.java.getDeclaredConstructor().run {
                isAccessible = true
                newInstance()
            }
            engine.densityScaleRaw = 1f
            engine.settingsEngine.renderDensity = 1f
            engine.settingsEngine.uiRenderScale = 1f
            block(engine)
        } finally {
            instanceField.set(null, previousEngine)
        }
    }

    private class TestSession(private val engine: GameEngine) : GameSession() {
        override val rendererMode = object : RendererMode { override val id = "test" }
        private val commands = ArrayDeque<(GameEngine) -> Unit>()

        override fun postEngineCommand(label: String, command: (GameEngine) -> Unit) {
            commands.addLast(command)
        }

        fun drainCommands() {
            while (commands.isNotEmpty()) commands.removeFirst()(engine)
        }

        override fun ensureStarted(viewport: KoolCanvasViewport) = engine
        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
        override fun loadPendingMapNow() = KoolCanvasFrame(KoolCanvasViewport(1280, 720), emptyList())
    }
}
