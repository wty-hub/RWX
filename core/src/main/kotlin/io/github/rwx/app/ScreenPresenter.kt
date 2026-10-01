package io.github.rwx.app

import de.fabmax.kool.input.InputStack
import de.fabmax.kool.modules.ui2.UiSurface
import de.fabmax.kool.scene.Node
import io.github.rwx.mod.registry.UiRegistry
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.AppScreenLayout

internal class ScreenPresenter(
    private val bootstrap: AppBootstrap,
    private val viewport: () -> KoolCanvasViewport,
) {
    fun shouldShowRwMenuBackground(screen: AppScreen): Boolean =
        screen == AppScreen.MainMenu &&
                bootstrap.settingsModel.showMainMenuBackgroundDemo.value &&
                bootstrap.menuBackgroundSession.isMenuBackgroundActive() &&
                !bootstrap.gameSession.canResume()

    fun apply(
        screen: AppScreen,
        lastExternalGameFrame: KoolCanvasFrame?,
    ) {
        val isMenuBackgroundPreparing = ensureMenuBackground(screen)
        val gameSession = bootstrap.gameSession
        val visibility = AppScreenLayout.visibilityFor(screen)
        val isRwMenuBackgroundVisible = shouldShowRwMenuBackground(screen)
        val isExternalModWindowOverlayVisible = screen == AppScreen.ModWindow && !gameSession.rendersIntoKoolCanvas
        val isExternalModHudOverlayVisible = shouldShowExternalModHudOverlay(
            hudVisible = visibility.hud,
            rendersIntoKoolCanvas = gameSession.rendersIntoKoolCanvas,
            hasActiveHudLayers = UiRegistry.hasActiveHudLayers(),
        )
        val isResumeBackgroundVisible = shouldShowResumeMenuBackground(screen, gameSession.canResume())
        val isExternalRwBackgroundVisible = shouldShowExternalRwBackgroundSurface(
            isRwMenuBackgroundVisible = isRwMenuBackgroundVisible,
            isResumeBackgroundVisible = isResumeBackgroundVisible,
            rendersIntoKoolCanvas = gameSession.rendersIntoKoolCanvas,
            usesNativeSurfaceForResumeBackground = gameSession.usesNativeSurfaceForResumeBackground,
        ) || (isMenuBackgroundPreparing && !gameSession.rendersIntoKoolCanvas)
        val isLastExternalFrameBackgroundVisible = screen == AppScreen.MainMenu &&
                !gameSession.rendersIntoKoolCanvas &&
                lastExternalGameFrame != null
        val isBattleBackgroundVisible = isRwMenuBackgroundVisible ||
                isResumeBackgroundVisible ||
                isLastExternalFrameBackgroundVisible

        bootstrap.mainMenuSceneHost.setBattleBackgroundVisible(isBattleBackgroundVisible)
        bootstrap.koolCanvasScene.setScreenVisible(visibility.world)
        bootstrap.modHudScene.setScreenVisible(visibility.hud)
        bootstrap.loadingScene.setScreenVisible(visibility.loading)

        bootstrap.mainMenuScene.setScreenVisible(visibility.mainMenu)
        bootstrap.levelSelectScene.setScreenVisible(visibility.levelSelect)
        bootstrap.replaySelectScene.setScreenVisible(visibility.replaySelect)
        bootstrap.settingsScene.setScreenVisible(visibility.settings)
        bootstrap.pauseScene.setScreenVisible(visibility.paused)
        bootstrap.multiplayerScene.setScreenVisible(visibility.multiplayer)
        bootstrap.modsScene.setScreenVisible(visibility.mods)
        bootstrap.resourceBrowserScene.setScreenVisible(visibility.resourceBrowser)
        bootstrap.battleRoomScene.setScreenVisible(visibility.battleRoom)
        bootstrap.modWindowScene.setScreenVisible(visibility.modWindow)
        gameSession.setGameVisible(
            shouldSetRwGameVisibleForScreen(screen) || isExternalRwBackgroundVisible,
            viewport(),
            koolOverlay = isExternalRwBackgroundVisible ||
                    isExternalModWindowOverlayVisible ||
                    isExternalModHudOverlayVisible ||
                    (screen == AppScreen.InGame && gameSession.compositesExternalGameFrameInKool),
            pausedBackground = shouldPauseRwGameForScreen(screen, isResumeBackgroundVisible),
        )
    }

    private fun ensureMenuBackground(screen: AppScreen): Boolean {
        if (!bootstrap.settingsModel.showMainMenuBackgroundDemo.value) return false
        if (screen != AppScreen.MainMenu) return false
        if (bootstrap.gameSession.canResume() || bootstrap.menuBackgroundSession.isMenuBackgroundActive()) return false
        bootstrap.menuBackgroundSession.prepareMenuBackgroundAsync(viewport())
        return true
    }
}

internal fun Node.setScreenVisible(visible: Boolean) {
    isVisible = visible
    if (!visible) removeScreenInputHandlers()
}

private fun Node.removeScreenInputHandlers() {
    if (this is UiSurface) {
        // Hidden scenes stop updating their surfaces, so UiSurface's own removal never runs.
        // Stage removal unconditionally: InputStack.remove misses pending pushTop registrations.
        // Leave capture modes intact so the surface can register normally when shown again.
        InputStack.handlerStack.stageRemove(inputHandler)
        inputHandler.requestFocus(null)
        isFocused.set(false)
    }
    children.forEach { it.removeScreenInputHandlers() }
}

internal fun shouldShowExternalModHudOverlay(
    hudVisible: Boolean,
    rendersIntoKoolCanvas: Boolean,
    hasActiveHudLayers: Boolean,
): Boolean = hudVisible && !rendersIntoKoolCanvas && hasActiveHudLayers
