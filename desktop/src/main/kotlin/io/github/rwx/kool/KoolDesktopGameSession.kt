package io.github.rwx.kool

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.map.TileMap
import com.corrodinggames.rts.gameFramework.GameEngine
import io.github.rwx.DesktopRendererMode
import io.github.rwx.PlatformStorage
import io.github.rwx.app.launchOnIO
import io.github.rwx.input.MultiTouchPointerState
import io.github.rwx.logger
import io.github.rwx.platform.CoreGameView
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.render.canvas.KoolGraphicsEngine
import io.github.rwx.session.GameSession
import io.github.rwx.session.GameSessionRendererProfile
import io.github.rwx.ui.BattleRoomUiBridge
import io.github.rwx.ui.InGameMenuController
import kotlin.math.roundToInt

/**
 * Desktop session that draws the game world through the Kool canvas command stream instead of the
 * AWT OpenGL canvas.
 *
 * Frames are produced on the Kool render loop ([updateFrame] runs the legacy game loop inline and
 * hands the Kool command buffer to the scene host), so no GL context exists and nothing is read
 * back with `glReadPixels`. On macOS the same Kool canvas is presented by the Vulkan backend, i.e.
 * MoltenVK on Metal; on Linux and Windows it is the regular Kool backend.
 *
 * Modelled on [io.github.rwx.AndroidGameSession]: the base class performs the asynchronous map /
 * save / replay preparation, this class only drives the loop and publishes frames.
 */
internal class KoolDesktopGameSession(
    private val storage: PlatformStorage,
) : GameSession() {
    override val rendererMode: RendererMode = DesktopRendererMode.Kool
    override val usesLogicalPointerCoordinates: Boolean = false

    private val graphicsEngine = KoolGraphicsEngine()
    private val view = KoolDesktopCoreGameView(inGameMenuController)
    private val frameTimeLog = KoolFrameTimeLog.fromEnvironment()

    private var appliedViewport = KoolCanvasViewport(0, 0)
    private var directoriesCreated = false

    @Volatile
    private var pausedForResumeBackground = false

    @Volatile
    private var resumeBackgroundFrameReady = false

    init {
        configureRendererProfile(
            GameSessionRendererProfile(
                rendersIntoKoolCanvas = true,
                acceptsKoolInput = true,
                canStartNewSessionInPlace = true,
                usesNativeSurfaceForResumeBackground = false,
            )
        )
    }

    override fun updateFrame(
        viewport: KoolCanvasViewport,
        deltaSeconds: Float,
        drainVisibleLayerBuffers: Boolean,
    ): KoolCanvasFrame {
        // A background load or a mod reload owns the engine: keep presenting the last frame instead
        // of touching the command buffer the loader may be using.
        if (loadState.asyncMapLoadInProgress || modReloadInProgress) {
            return lastFrame
        }
        synchronized(gameLock) {
            frameTimeLog?.beginFrame()
            lastViewport = viewport
            val engine = ensureStarted(viewport)
            applyViewport(engine, viewport)
            loadPendingMap(engine)

            if (pausedForResumeBackground) {
                // The menu shows a frozen world behind it; the regular loop stays stopped.
                if (!resumeBackgroundFrameReady) {
                    renderPausedBackgroundFrame(engine, viewport)
                }
                return lastFrame
            }

            graphicsEngine.beginFrame(viewport.width.coerceAtLeast(1), viewport.height.coerceAtLeast(1))
            engine.renderGraphicsEngine = graphicsEngine
            runGameLoop(engine, deltaSeconds)
            frameTimeLog?.endGameWork()
            if (drainVisibleLayerBuffers && engine.hasLoadedLevel) {
                TileMap.layerBufferManager.renderVisiblePendingRedrawsNow()
            }
            frameTimeLog?.endLayerRedraw()
            lastFrame = graphicsEngine.snapshot()
            frameTimeLog?.endSnapshot()
            return lastFrame
        }
    }

    override fun currentFrame(): KoolCanvasFrame = lastFrame

    override fun loadPendingMapNow(): KoolCanvasFrame = updateFrame(lastViewport, 0f)

    override fun setGameVisible(
        visible: Boolean,
        viewport: KoolCanvasViewport,
        koolOverlay: Boolean,
        pausedBackground: Boolean,
    ) {
        if (viewport.width > 0 && viewport.height > 0) {
            lastViewport = viewport
        }
        val shouldPauseForBackground = synchronized(gameLock) {
            val engine = activeEngineLocked()
            pausedBackground &&
                engine?.hasLoadedLevel == true &&
                !engine.isMenuBackgroundMap &&
                !engine.isNetworkGameActive()
        }
        if (pausedForResumeBackground == shouldPauseForBackground) {
            return
        }
        pausedForResumeBackground = shouldPauseForBackground
        resumeBackgroundFrameReady = false
        if (shouldPauseForBackground) {
            // The resume background is presented through [currentFrame] while the menu is up, so
            // the frozen world has to be drawn here rather than on the next visible-game frame.
            synchronized(gameLock) {
                val engine = activeEngineLocked() ?: return
                renderPausedBackgroundFrame(engine, lastViewport)
            }
        }
    }

    override fun submitPointer(screenX: Float, screenY: Float, isDown: Boolean, pointerId: Int) {
        view.submitPointer(screenX, screenY, isDown, pointerId)
    }

    override fun movePointer(screenX: Float, screenY: Float) {
        view.movePointer(screenX, screenY)
    }

    override fun clearInputState() {
        view.submitPointer(0f, 0f, false, -1)
    }

    override fun prepareMenuBackgroundAsync(viewport: KoolCanvasViewport) {
        val state = loadState
        if (state.menuBackgroundActive) {
            return
        }
        if (state.runningMapPath != null && gameEngine?.hasLoadedLevel == true) {
            return
        }
        var generation: Long? = null
        updateLoadState { current ->
            if (current.asyncMapLoadInProgress || current.menuBackgroundActive) {
                generation = null
                current
            } else {
                current.copy(
                    mapLoadGeneration = current.mapLoadGeneration + 1,
                    asyncMapLoadPath = MENU_BACKGROUND_REQUEST,
                    asyncMapLoadInProgress = true,
                    asyncMapLoadError = null,
                ).also { generation = it.mapLoadGeneration }
            }
        }
        val issuedGeneration = generation ?: return
        lastFrame = KoolCanvasFrame(viewport, emptyList())
        logger.info { "Preparing $sessionLogName RW menu background asynchronously" }
        launchOnIO("${rendererMode.id}-menu-background-loader") {
            loadMenuBackgroundInBackground(viewport, issuedGeneration)
        }
    }

    override fun isMenuBackgroundActive(): Boolean {
        val state = loadState
        return state.menuBackgroundActive ||
                (state.asyncMapLoadInProgress && state.asyncMapLoadPath == MENU_BACKGROUND_REQUEST)
    }

    override fun adoptStartedGameFromEngine(viewport: KoolCanvasViewport): Boolean =
        synchronized(gameLock) {
            val engine = gameEngine ?: GameEngine.getInstance() ?: return@synchronized false
            if (engine.networkEngine?.gameHasBeenStarted != true) return@synchronized false

            lastViewport = viewport
            if (gameEngine == null) {
                gameEngine = engine
            }
            applyViewport(engine, viewport)

            // The original battleroom runs startGameCommon() before opening the game surface, and
            // there is no renderer callback equivalent to Slick's to finish that load here.
            BattleRoomUiBridge.setupGame()
            val activeMapPath = activeRunningMapPath(engine) ?: return@synchronized false

            updateLoadState {
                it.copy(
                    pendingMapPath = null,
                    asyncMapLoadPath = null,
                    asyncMapLoadInProgress = false,
                    asyncMapLoadError = null,
                    menuBackgroundActive = false,
                    runningMapPath = activeMapPath,
                )
            }
            engine.isStopped = false
            engine.isPaused = false
            true
        }

    protected override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine =
        synchronized(gameLock) {
            activeEngineLocked() ?: ensureRendererEngine(viewport, graphicsEngine, view).also {
                if (!directoriesCreated) {
                    directoriesCreated = true
                    storage.createDirectories()
                }
            }
        }

    protected override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) {
        val width = viewport.width.coerceAtLeast(1)
        val height = viewport.height.coerceAtLeast(1)
        if (appliedViewport.width == width && appliedViewport.height == height) {
            return
        }
        engine.updateWindowResolution(width, height)
        graphicsEngine.a(width, height)
        view.onSizeChanged()
        appliedViewport = KoolCanvasViewport(width, height)
    }

    private fun runGameLoop(engine: GameEngine, deltaSeconds: Float) {
        runCatching {
            engine.gameLoop(
                deltaSeconds.toGameSpeedDelta(),
                (deltaSeconds * 1000f).roundToInt().coerceAtLeast(0),
            )
        }.onFailure { error ->
            logger.error(error) { "$sessionLogName game loop failed" }
        }
    }

    /**
     * Draws the world without the in-game HUD and publishes it as the frame behind the menu.
     * Must run under [gameLock]: it replaces the command buffer contents and [lastFrame].
     */
    private fun renderPausedBackgroundFrame(engine: GameEngine, viewport: KoolCanvasViewport) {
        if (!engine.hasLoadedLevel || viewport.width <= 0 || viewport.height <= 0) {
            return
        }
        graphicsEngine.beginFrame(viewport.width, viewport.height)
        engine.renderGraphicsEngine = graphicsEngine
        runCatching {
            (engine as GameLogic).drawWorldOnlyThreadSafe(0f)
        }.onFailure { error ->
            logger.error(error) { "$sessionLogName paused background render failed" }
        }
        resumeBackgroundFrameReady = true
        lastFrame = graphicsEngine.snapshot()
    }

    private fun loadMenuBackgroundInBackground(requestedViewport: KoolCanvasViewport, generation: Long) {
        val startedAt = System.nanoTime()
        var loadedCurrentRequest = false
        runCatching {
            synchronized(gameLock) {
                if (generation != loadState.mapLoadGeneration) {
                    return@synchronized
                }
                val viewport = requestedViewport.takeIf { it.width > 0 && it.height > 0 }
                    ?: defaultPreloadViewport
                lastViewport = viewport
                val engine = ensureStarted(viewport)
                applyViewport(engine, viewport)
                engine.isStopped = true
                engine.isPaused = true
                engine.loadMenuBackground()
                if (!engine.hasLoadedLevel || !engine.isMenuBackgroundMap) {
                    error("RW menu background map did not load")
                }
                engine.targetZoom *= MENU_BACKGROUND_ZOOM_MULTIPLIER
                engine.zoom = engine.targetZoom * engine.densityZoomScale
                engine.updateWindowResolution(
                    engine.screenWidth.toInt(),
                    engine.screenHeight.toInt(),
                    engine.renderSurfaceScale,
                )
                val currentMapPath = engine.currentMapPath
                updateLoadState { current ->
                    if (current.mapLoadGeneration == generation) {
                        current.copy(runningMapPath = currentMapPath, menuBackgroundActive = true)
                            .also { loadedCurrentRequest = true }
                    } else {
                        current
                    }
                }
            }
            if (loadedCurrentRequest) {
                logger.info {
                    "Prepared $sessionLogName RW menu background: ${loadState.runningMapPath ?: "<unknown>"} " +
                            "(${elapsedMs(startedAt)}ms)"
                }
            } else {
                logger.info { "Discarded stale $sessionLogName RW menu background preparation" }
            }
        }.onFailure { error ->
            if (loadState.mapLoadGeneration == generation) {
                updateLoadState { current ->
                    if (current.mapLoadGeneration == generation) {
                        current.copy(menuBackgroundActive = false, asyncMapLoadError = error)
                    } else {
                        current
                    }
                }
                logger.error(error) { "$sessionLogName RW menu background load failed" }
            }
        }.also {
            updateLoadState { current ->
                if (current.mapLoadGeneration == generation) {
                    current.copy(asyncMapLoadInProgress = false, asyncMapLoadPath = null)
                } else {
                    current
                }
            }
        }
    }

    private class KoolDesktopCoreGameView(
        private val menuController: InGameMenuController,
    ) : CoreGameView {
        private val pointerState = MultiTouchPointerState()
        private var rendering = true

        override fun pause() {
            rendering = false
        }

        // The session drives the loop itself, so the engine must always take its external-driver
        // branch; pause/resume is expressed through the session's own visibility state.
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

        fun submitPointer(screenX: Float, screenY: Float, isDown: Boolean, pointerId: Int) {
            pointerState.processEvent(screenX, screenY, isDown, pointerId)
        }

        fun movePointer(screenX: Float, screenY: Float) {
            pointerState.setStart(screenX, screenY)
        }
    }

    private companion object {
        const val MENU_BACKGROUND_REQUEST = "<menu-background>"
        const val MENU_BACKGROUND_ZOOM_MULTIPLIER = 2f
    }
}
