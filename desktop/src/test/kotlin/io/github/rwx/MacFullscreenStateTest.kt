package io.github.rwx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MacFullscreenStateTest {
    @Test
    fun `native green button enter and exit each recover the surface and update settings`() {
        val settings = mutableListOf<Boolean>()
        var recoveries = 0
        var toggles = 0
        val state = MacFullscreenState({ toggles++ }, { settings.add(it) }, { recoveries++ })
        state.started(true)
        assertTrue(state.transitioning)
        state.request(true) // Saved setting now matches the native action.
        state.completed(true)
        assertTrue(state.fullscreen)
        assertFalse(state.transitioning)
        state.started(false)
        state.request(false)
        state.completed(false)
        assertFalse(state.fullscreen)
        assertEquals(listOf(true, false), settings)
        assertEquals(2, recoveries)
        assertEquals(0, toggles, "A native button click must not trigger a second toggle")
    }

    @Test
    fun `startup fullscreen and repeated settings sync only request one Cocoa toggle`() {
        var toggles = 0
        val state = MacFullscreenState({ toggles++ }, {}, {})
        repeat(10) { state.request(true) }
        assertEquals(1, toggles)
        state.started(true)
        state.completed(true)
        repeat(10) { state.request(true) }
        assertEquals(1, toggles)
    }

    @Test
    fun `settings change during animation is applied after transition completion`() {
        var toggles = 0
        val state = MacFullscreenState({ toggles++ }, {}, {})
        state.request(true)
        state.started(true)
        state.request(false)
        assertEquals(1, toggles)
        state.completed(true)
        assertEquals(2, toggles)
        assertTrue(state.transitioning)
        state.started(false)
        state.completed(false)
        assertFalse(state.fullscreen)
        assertFalse(state.transitioning)
    }
}
