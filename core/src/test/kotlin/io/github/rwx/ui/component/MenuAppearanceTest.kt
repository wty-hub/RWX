package io.github.rwx.ui.component

import io.github.rwx.ui.ColorSchemeRegistry
import io.github.rwx.ui.UiAppearance
import kotlin.test.Test
import kotlin.test.assertEquals

class MenuAppearanceTest {
    @Test
    fun `cyberpunk surfaces do not replace in-game colors`() {
        val inGameColor = ColorSchemeRegistry.schemes.first { it.id.value == "amber-blue" }.id

        assertEquals(UiAppearance.Cyberpunk, visualScheme(PanelStyle.Menu, inGameColor).appearance)
        assertEquals(UiAppearance.Cyberpunk, visualScheme(PanelStyle.Dialog, inGameColor).appearance)
        assertEquals(UiAppearance.Cyberpunk, visualScheme(PanelStyle.Snackbar, inGameColor).appearance)
        assertEquals(UiAppearance.Cyberpunk, visualScheme(PanelStyle.Pause, inGameColor).appearance)
        assertEquals(inGameColor, visualScheme(PanelStyle.Hud, inGameColor).id)
        assertEquals(inGameColor, visualScheme(PanelStyle.ModWindow, inGameColor).id)
    }
}
