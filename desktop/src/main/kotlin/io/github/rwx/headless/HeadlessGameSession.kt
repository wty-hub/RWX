package io.github.rwx.headless

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import io.github.rwx.input.MultiTouchPointerState
import io.github.rwx.platform.CoreGameView
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameSession
import io.github.rwx.ui.InGameMenuController

/**
 * Boots the existing session loader without a window. [CoreGameView.isPaused] stays true so
 * [com.corrodinggames.rts.game.GameLogic.gameLoop] takes the same update branch as the Slick host.
 */
class HeadlessGameSession : GameSession() {
    override val rendererMode: RendererMode = HeadlessRendererMode

    fun boot(graphicsEngine: GraphicsEngine): GameEngine =
        ensureRendererEngine(
            viewport = KoolCanvasViewport(VIEWPORT_WIDTH, VIEWPORT_HEIGHT),
            graphicsEngine = graphicsEngine,
            view = HeadlessCoreGameView(inGameMenuController),
        )

    fun openReplay(name: String) {
        loadReplay(requireEngine(), name)
    }

    fun openMap(path: String) {
        val engine = requireEngine()
        loadMap(engine, path)
        check(engine.hasLoadedLevel) { "Unable to load map: $path" }
    }

    override fun loadPendingMapNow(): KoolCanvasFrame = currentFrame()

    override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine =
        gameEngine ?: error("Headless engine is not started")

    override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) {
        val width = viewport.width.coerceAtLeast(1)
        val height = viewport.height.coerceAtLeast(1)
        engine.updateWindowResolution(width, height)
    }

    private fun requireEngine(): GameEngine =
        gameEngine ?: error("Headless engine is not started")

    private class HeadlessCoreGameView(
        private val menuController: InGameMenuController,
    ) : CoreGameView {
        private val pointerState = MultiTouchPointerState()
        private var rendering = true

        override fun pause() {
            rendering = false
        }

        override fun isPaused(): Boolean = true

        override fun isContinuousRendering(): Boolean = true

        override fun isRendering(): Boolean = rendering

        override fun getInGameMenuController(): InGameMenuController = menuController

        override fun onResume() {
            rendering = true
        }

        override fun getSettings(): MultiTouchPointerState = pointerState

        override fun onSizeChanged() = Unit

        override fun stopRender() {
            rendering = false
        }
    }

    private object HeadlessRendererMode : RendererMode {
        override val id: String = "headless"
    }

    private companion object {
        const val VIEWPORT_WIDTH = 1280
        const val VIEWPORT_HEIGHT = 720
    }
}
