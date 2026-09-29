package io.github.rwx.settings

import com.corrodinggames.rts.gameFramework.SettingsEngine
import io.github.rwx.ui.model.SettingsModel
import io.github.rwx.ui.model.SettingsPage
import io.github.rwx.ui.model.SettingsPageItem
import io.github.rwx.ui.model.SettingsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class UiScaleSettingsTest {
    @Test
    fun `interface scale is clamped to the supported range`() {
        assertEquals(1.0f, SettingsEngine.normalizeUiRenderScale(0.0f))
        assertEquals(1.0f, SettingsEngine.normalizeUiRenderScale(-1.0f))
        assertEquals(1.0f, SettingsEngine.normalizeUiRenderScale(Float.NaN))
        assertEquals(SettingsEngine.MIN_UI_RENDER_SCALE, SettingsEngine.normalizeUiRenderScale(0.1f))
        assertEquals(SettingsEngine.MAX_UI_RENDER_SCALE, SettingsEngine.normalizeUiRenderScale(9.0f))
        assertEquals(1.25f, SettingsEngine.normalizeUiRenderScale(1.25f))
    }

    @Test
    fun `saved interface scale is loaded and out of range values are clamped`() {
        val storage = MemoryPreferenceStorage()
        val repository = GameSettingsRepository(storage)
        assertEquals(1.0f, SettingsModel().also(repository::loadInto).uiScale.value)

        storage.preference("preferences").putFloat("uiRenderScale", 1.5f)
        assertEquals(1.5f, SettingsModel().also(repository::loadInto).uiScale.value)

        storage.preference("preferences").putFloat("uiRenderScale", 4f)
        assertEquals(SettingsEngine.MAX_UI_RENDER_SCALE, SettingsModel().also(repository::loadInto).uiScale.value)
    }

    @Test
    fun `the display page exposes the interface scale slider bound to the model value`() {
        val model = SettingsModel()
        val page = SettingsViewModel(model).pageAt(SettingsPage.Display.ordinal)

        val slider = page.items
            .filterIsInstance<SettingsPageItem.Slider>()
            .firstOrNull { it.slider.state === model.uiScale }

        val exposed = assertNotNull(slider, "Display page should expose the interface scale slider")
        assertEquals(SettingsEngine.MIN_UI_RENDER_SCALE, exposed.slider.min)
        assertEquals(SettingsEngine.MAX_UI_RENDER_SCALE, exposed.slider.max)
        assertSame(model.uiScale, exposed.slider.state)
    }
}
