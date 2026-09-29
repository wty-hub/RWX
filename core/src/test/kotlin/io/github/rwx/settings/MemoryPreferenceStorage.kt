package io.github.rwx.settings

import io.github.rwx.Preference
import io.github.rwx.PreferenceStorage

/** In-memory [PreferenceStorage] for settings tests; values are stored per preference name. */
internal class MemoryPreferenceStorage : PreferenceStorage {
    private val names = mutableMapOf<String, MutableMap<String, String>>()

    override fun preference(name: String): Preference {
        val values = names.getOrPut(name) { mutableMapOf() }
        return object : Preference {
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
    }

    override fun flush() = Unit
}
