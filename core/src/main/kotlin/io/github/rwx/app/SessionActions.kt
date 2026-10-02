package io.github.rwx.app

import io.github.rwx.mod.registry.UiRegistry
import io.github.rwx.session.GameSession
import io.github.rwx.settings.GameSettingsRepository
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.host.SettingsSceneHost
import io.github.rwx.ui.model.SettingsModel
import io.github.rwx.ui.model.SettingsPage

internal class SessionActions(
    private val gameSession: GameSession,
    private val warmupController: WarmupController,
    private val pendingStartController: PendingStartController,
    private val settingsSceneHost: SettingsSceneHost,
    private val settingsRepository: GameSettingsRepository,
    private val settingsModel: SettingsModel,
    private val modsController: ModsController,
    private val currentScreen: () -> AppScreen,
    private val navigateTo: (AppScreen) -> Unit,
    private val refreshMainMenu: () -> Unit,
    private val onQuit: () -> Unit,
) {
    fun exitRwGameToMainMenu() = exitRwGame(isMultiplayer = false)

    fun exitRwGame(isMultiplayer: Boolean) {
        warmupController.clear()
        pendingStartController.clear()
        navigateTo(gameExitDestination(isMultiplayer))
        if (!isMultiplayer) refreshMainMenu()
    }

    fun returnRwGameToBattleRoom() {
        warmupController.clear()
        pendingStartController.clear()
        navigateTo(AppScreen.BattleRoom)
    }

    fun openInGameSettings() {
        openSettings(AppScreen.InGame)
    }

    fun openMainMenuSettings() {
        openSettings(AppScreen.MainMenu)
    }

    /**
     * Screen the settings page returns to. Reset on every open so a page that was entered from the
     * pause menu never sends a later main-menu visit back into the match.
     */
    var settingsBackTarget: AppScreen = AppScreen.MainMenu
        private set

    private fun openSettings(backTarget: AppScreen) {
        settingsBackTarget = backTarget
        settingsSceneHost.showPage(SettingsPage.Display)
        navigateTo(AppScreen.Settings)
    }

    fun requestInGameSurrender() {
        gameSession.requestSurrender()
        navigateTo(AppScreen.InGame)
    }

    fun openInGameModWindow() = navigateTo(AppScreen.ModWindow)

    fun closeInGameModWindow() {
        UiRegistry.deactivateWindow()
        navigateTo(AppScreen.InGame)
    }

    fun quitApp() {
        warmupController.clear()
        pendingStartController.clear()
        gameSession.discardRunningGame()
        onQuit()
    }

    fun saveCurrentScreenStateBeforeMainMenu() {
        when (currentScreen()) {
            AppScreen.Settings -> settingsRepository.saveFrom(settingsModel)
            AppScreen.Mods -> modsController.applyChangesAndRefresh()
            else -> Unit
        }
    }
}
