package io.github.rwx.app

import io.github.rwx.ui.AppScreen
import kotlin.test.Test
import kotlin.test.assertEquals

class NavigationRulesTest {
    @Test
    fun `escape from the battle room closes the room`() {
        assertEquals(
            BackNavigationAction.CloseBattleRoom,
            backActionForScreen(AppScreen.BattleRoom, rendersIntoKoolCanvas = true),
        )
        assertEquals(
            BackNavigationAction.CloseBattleRoom,
            backActionForScreen(AppScreen.BattleRoom, rendersIntoKoolCanvas = false),
        )
    }

    @Test
    fun `escape from other screens keeps the existing destinations`() {
        assertEquals(
            BackNavigationAction.MainMenu,
            backActionForScreen(AppScreen.Multiplayer, rendersIntoKoolCanvas = true),
        )
        assertEquals(
            BackNavigationAction.Pause,
            backActionForScreen(AppScreen.InGame, rendersIntoKoolCanvas = true),
        )
        assertEquals(
            BackNavigationAction.ShowExitDialog,
            backActionForScreen(AppScreen.InGame, rendersIntoKoolCanvas = false),
        )
        assertEquals(
            BackNavigationAction.ShowExitDialog,
            backActionForScreen(AppScreen.Paused, rendersIntoKoolCanvas = true),
        )
    }
}
