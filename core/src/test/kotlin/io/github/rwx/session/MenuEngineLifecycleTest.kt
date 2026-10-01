package io.github.rwx.session

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.PerformanceProfiler
import com.corrodinggames.rts.gameFramework.network.NetworkEngine
import io.github.rwx.platform.CoreGameView
import java.lang.reflect.Proxy
import kotlin.test.*

class MenuEngineLifecycleTest {
    @Test
    fun `menu preloading keeps servicing frames without repeatedly closing an unloaded game`() = withEngine { engine ->
        engine.colorizeLogMessage(view(), false)
        var tasksRun = 0
        repeat(120) {
            engine.pendingGameThreadTasks.a(Runnable { tasksRun++ })
            engine.gameThreadRunnableQueue.add(Runnable { tasksRun++ })
            engine.gameLoop(1f, 16)
        }

        assertTrue(engine.isStopped)
        assertTrue(engine.isPaused)
        assertFalse(engine.hasLoadedLevel)
        assertEquals(240, tasksRun)
        assertEquals(0, engine.closeRequests)
        assertEquals(0, engine.backgroundLoads, "View attachment must not implicitly load a demo map")
    }

    @Test
    fun `a loaded game can resume on the same view after menu preloading`() = withEngine { engine ->
        val view = view()
        engine.colorizeLogMessage(view, false)
        assertTrue(engine.isStopped)

        engine.hasLoadedLevel = true
        engine.colorizeLogMessage(view, false)

        assertFalse(engine.isStopped)
        assertFalse(engine.isPaused)
        engine.colorizeLogMessage(view, true)
        assertTrue(engine.isStopped)
        assertTrue(engine.isPaused)
    }

    private class TestEngine : GameLogic() {
        var closeRequests = 0
        var backgroundLoads = 0
        override fun startGameThread() = Unit
        override fun stopAndClose() { closeRequests++ }
        override fun loadMenuBackground() { backgroundLoads++ }
    }

    private fun view(): CoreGameView = Proxy.newProxyInstance(
        CoreGameView::class.java.classLoader, arrayOf(CoreGameView::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "isPaused" -> true
            "isContinuousRendering", "isRendering" -> false
            else -> null
        }
    } as CoreGameView

    private fun withEngine(action: (TestEngine) -> Unit) {
        val instance = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previous = instance.get(null)
        val external = GameEngine.externalGameLoopDriver
        instance.set(null, null)
        GameEngine.externalGameLoopDriver = true
        try {
            val engine = TestEngine()
            engine.networkEngine = NetworkEngine()
            engine.performanceProfiler = PerformanceProfiler(engine)
            action(engine)
        } finally {
            instance.set(null, previous)
            GameEngine.externalGameLoopDriver = external
        }
    }
}
