package io.github.rwx.ui.host

import de.fabmax.kool.modules.ui2.Row
import de.fabmax.kool.modules.ui2.UiScene
import de.fabmax.kool.modules.ui2.mutableStateListOf
import de.fabmax.kool.scene.Scene
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.component.*
import io.github.rwx.ui.model.*

/**
 * Renders the pause menu in pure Kool DSL: a centered button stack (Resume, Save?, Settings,
 * Surrender, Exit Game) matching the classic in-game menu layout from menus.ingame.* keys.
 * Cross-platform — kool-core common APIs only, valid on desktop and Android.
 */
class PauseMenuSceneHost(
    private val model: SettingsModel = SettingsModel(),
    private val onAction: (PauseMenuAction) -> Unit = {},
) {
    var items: List<PauseMenuItem> = emptyList()
        private set

    private val menuItems = mutableStateListOf<PauseMenuItem>()

    fun updateItems(conditions: PauseMenuConditions) {
        val next = PauseMenuViewModel.items(conditions)
        if (next != items) {
            items = next
            menuItems.atomic {
                clear()
                addAll(next)
            }
        }
    }

    fun dispatch(action: PauseMenuAction) = onAction(action)

    fun createScene(): Scene = UiScene(PAUSE_SCENE_NAME) {
        addPanelSurface(PanelStyle.Pause, "pause-panel", model) { theme ->
            menuItems.use().forEach { item ->
                TextIconButton(
                    label = item.label,
                    icon = item.action.pauseIcon,
                    width = UiTheme.Layout.menuButtonWidth,
                    theme = theme,
                    showIcon = false,
                ) {
                    dispatch(item.action)
                }
            }
        }
    }

    companion object {
        const val PAUSE_SCENE_NAME: String = "pause"
    }
}

private val PauseMenuAction.pauseIcon: Icon
    get() = when (this) {
        PauseMenuAction.Resume -> Icon.Continue
        PauseMenuAction.Save -> Icon.Save
        PauseMenuAction.Settings -> Icon.Settings
        PauseMenuAction.Chat -> Icon.Send
        PauseMenuAction.Players -> Icon.Multiplayer
        PauseMenuAction.Surrender -> Icon.Surrender
        PauseMenuAction.ExitGame -> Icon.Exit
    }
