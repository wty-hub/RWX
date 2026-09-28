package io.github.rwx.slick

import kotlin.test.Test
import kotlin.test.assertEquals

class MaxFrameRateTest {
    @Test
    fun `manual choice sets the desktop target frame rate`() {
        for (fps in listOf(30, 60, 120, 144, 240, 300)) {
            assertEquals(fps, resolveSlickTargetFrameRate(fps, highRefreshRate = true, environmentOverride = null))
        }
    }

    @Test
    fun `auto keeps the existing high refresh behavior`() {
        assertEquals(120, resolveSlickTargetFrameRate(0, highRefreshRate = false, environmentOverride = null))
        assertEquals(300, resolveSlickTargetFrameRate(0, highRefreshRate = true, environmentOverride = null))
        assertEquals(300, resolveSlickTargetFrameRate(75, highRefreshRate = true, environmentOverride = null))
    }

    @Test
    fun `environment override has the highest priority`() {
        assertEquals(75, resolveSlickTargetFrameRate(60, highRefreshRate = false, environmentOverride = 75))
    }
}
