package io.github.rwx.ui.model

import io.github.rwx.ui.AppScreen
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsNavigationTest {
    @Test
    fun `leaving settings returns to the screen the page was opened from`() {
        assertEquals(
            SettingsOutcome.Navigate(AppScreen.MainMenu),
            SettingsNavigation.outcomeFor(SettingsAction.Back),
        )
        assertEquals(
            SettingsOutcome.Navigate(AppScreen.MainMenu),
            SettingsNavigation.outcomeFor(SettingsAction.Back, AppScreen.MainMenu),
        )
        assertEquals(
            SettingsOutcome.Navigate(AppScreen.InGame),
            SettingsNavigation.outcomeFor(SettingsAction.Back, AppScreen.InGame),
        )
    }

    @Test
    fun `preview and apply do not navigate anywhere`() {
        assertEquals(
            SettingsOutcome.PreviewChanges,
            SettingsNavigation.outcomeFor(SettingsAction.PreviewChanges, AppScreen.InGame),
        )
        assertEquals(
            SettingsOutcome.ApplyChanges,
            SettingsNavigation.outcomeFor(SettingsAction.ApplyChanges, AppScreen.InGame),
        )
    }
}
