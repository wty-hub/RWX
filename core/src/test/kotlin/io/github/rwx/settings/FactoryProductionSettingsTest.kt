package io.github.rwx.settings

import io.github.rwx.PREFERENCE_NAME
import io.github.rwx.ui.model.SettingsModel
import io.github.rwx.ui.model.SettingsPage
import io.github.rwx.ui.model.SettingsPageItem
import io.github.rwx.ui.model.SettingsViewModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FactoryProductionSettingsTest {
    @Test
    fun `production defaults to all selected factories`() {
        val repository = GameSettingsRepository(MemoryPreferenceStorage())
        assertFalse(SettingsModel().singleUnitProduction.value)
        assertFalse(SettingsModel().also(repository::loadInto).singleUnitProduction.value)
    }

    @Test
    fun `both modes survive saving and reloading preferences`() {
        val storage = MemoryPreferenceStorage()
        val repository = GameSettingsRepository(storage)
        // Defer engine mutations to exercise the preferences independently of a running game.
        repository.engineExecutor = { }
        val model = SettingsModel()
        for (singleUnit in listOf(true, false)) {
            model.singleUnitProduction.value = singleUnit
            repository.saveFrom(model)
            assertEquals(singleUnit, storage.preference(PREFERENCE_NAME).getBoolean("singleUnitProduction", !singleUnit))
            assertEquals(singleUnit, SettingsModel().also(repository::loadInto).singleUnitProduction.value)
        }
    }

    @Test
    fun `gameplay settings expose the production mode selector`() {
        val page = SettingsViewModel(SettingsModel()).pageAt(SettingsPage.Gameplay.ordinal)
        assertTrue(SettingsPageItem.FactoryProduction in page.items)
    }
}
