package io.github.rwx

import kotlin.test.Test
import kotlin.test.assertEquals

class MaxFrameRateTest {
    @Test
    fun `manual choice sets the desktop target frame rate`() {
        for (fps in listOf(30, 60, 120, 144, 240, 300)) {
            assertEquals(fps, resolveDesktopTargetFrameRate(fps, highRefreshRate = true, environmentOverride = null))
        }
    }

    @Test
    fun `auto keeps the existing high refresh behavior`() {
        assertEquals(120, resolveDesktopTargetFrameRate(0, highRefreshRate = false, environmentOverride = null))
        assertEquals(300, resolveDesktopTargetFrameRate(0, highRefreshRate = true, environmentOverride = null))
        assertEquals(300, resolveDesktopTargetFrameRate(75, highRefreshRate = true, environmentOverride = null))
    }

    @Test
    fun `environment override has the highest priority`() {
        assertEquals(75, resolveDesktopTargetFrameRate(60, highRefreshRate = false, environmentOverride = 75))
    }
}
