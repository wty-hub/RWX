package io.github.rwx.session

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BattleRoomAsyncLoadTest {
    @Test
    fun `async sandbox loading initializes the room even when the same map was already loaded`() {
        val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousEngine = instanceField.get(null)
        instanceField.set(null, null)
        try {
            val engine = GameLogic()
            val config = BattleRoomLaunchConfig(
                sandbox = true,
                room = BattleRoomCoreConfig("sandbox.tmx", GameRoomSettings()),
            )
            val session = RecordingSession(engine, config)

            session.prepareBattleRoomAsync(config, KoolCanvasViewport(1280, 720))

            assertTrue(session.loaded.await(10, TimeUnit.SECONDS), "Room load did not complete")
            assertEquals(listOf(config), session.roomLoads)
            assertEquals(0, session.plainMapLoads)
        } finally {
            instanceField.set(null, previousEngine)
        }
    }

    private class RecordingSession(private val engine: GameEngine, config: BattleRoomLaunchConfig) : GameSession() {
        override val rendererMode = object : RendererMode { override val id = "test" }
        val loaded = CountDownLatch(1)
        val roomLoads = mutableListOf<BattleRoomLaunchConfig>()
        var plainMapLoads = 0

        init {
            gameEngine = engine
            engine.hasLoadedLevel = true
            updateLoadState {
                it.copy(runningMapPath = config.room.mapPath, activeRendererBattleRoomConfig = config)
            }
        }

        override fun loadBattleRoomMap(engine: GameEngine, config: BattleRoomLaunchConfig) {
            roomLoads += config
        }
        override fun loadMap(engine: GameEngine, mapPath: String) {
            plainMapLoads++
        }
        override fun prepareFrameAfterBackgroundLoad(engine: GameEngine, viewport: KoolCanvasViewport): KoolCanvasFrame {
            loaded.countDown()
            return KoolCanvasFrame(viewport, emptyList())
        }
        override fun ensureStarted(viewport: KoolCanvasViewport) = engine
        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
        override fun loadPendingMapNow() = KoolCanvasFrame(KoolCanvasViewport(1280, 720), emptyList())
    }
}
