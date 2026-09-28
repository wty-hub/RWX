package io.github.rwx.settings

import com.corrodinggames.rts.gameFramework.SettingsEngine
import io.github.rwx.Preference
import io.github.rwx.PreferenceStorage
import io.github.rwx.ui.model.SettingsModel
import kotlin.test.Test
import kotlin.test.assertEquals

class MaxFrameRateSettingsTest {
    @Test
    fun `only supported frame rate values are accepted`() {
        for (value in listOf(0, 30, 60, 120, 144, 240, 300)) {
            assertEquals(value, SettingsEngine.normalizeMaxFrameRate(value))
        }
        for (value in listOf(-1, 1, 75, 301)) {
            assertEquals(0, SettingsEngine.normalizeMaxFrameRate(value))
        }
    }

    @Test
    fun `missing and invalid saved values use auto`() {
        val storage = MemoryPreferenceStorage()
        val repository = GameSettingsRepository(storage)
        assertEquals(0, SettingsModel().also(repository::loadInto).maxFrameRate.value)

        storage.preference("preferences").putInt("maxFrameRate", 75)
        assertEquals(0, SettingsModel().also(repository::loadInto).maxFrameRate.value)
    }

    @Test
    fun `saved frame rate is loaded into the settings model`() {
        val storage = MemoryPreferenceStorage()
        val repository = GameSettingsRepository(storage)
        storage.preference("preferences").putInt("maxFrameRate", 144)

        assertEquals(144, SettingsModel().also(repository::loadInto).maxFrameRate.value)
    }

    private class MemoryPreferenceStorage : PreferenceStorage {
        private val values = mutableMapOf<String, String>()
        private val preference = object : Preference {
            override fun getBoolean(key: String, defValue: Boolean) = values[key]?.toBooleanStrictOrNull() ?: defValue
            override fun getInt(key: String, defValue: Int) = values[key]?.toIntOrNull() ?: defValue
            override fun getLong(key: String, defValue: Long) = values[key]?.toLongOrNull() ?: defValue
            override fun getFloat(key: String, defValue: Float) = values[key]?.toFloatOrNull() ?: defValue
            override fun getString(key: String, defValue: String?) = values[key] ?: defValue
            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value.toString() }
            override fun putInt(key: String, value: Int) = apply { values[key] = value.toString() }
            override fun putLong(key: String, value: Long) = apply { values[key] = value.toString() }
            override fun putFloat(key: String, value: Float) = apply { values[key] = value.toString() }
            override fun putString(key: String, value: String?) = apply {
                if (value == null) values.remove(key) else values[key] = value
            }
            override fun contains(key: String) = values.containsKey(key)
            override fun remove(key: String) = values.remove(key)
            override fun clear() = values.clear()
        }

        override fun preference(name: String): Preference = preference
        override fun flush() = Unit
    }
}
