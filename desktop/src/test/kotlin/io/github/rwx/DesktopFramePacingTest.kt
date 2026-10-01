package io.github.rwx

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopFramePacingTest {
    @Test
    fun `manual limits apply above and below the monitor refresh rate`() {
        for (fps in listOf(30, 60, 120, 144, 240, 300)) {
            assertEquals(1_000_000_000L / fps, desktopFramePeriodNanos(fps, vsync = false, refreshRate = 60))
        }
    }

    @Test
    fun `vertical sync respects a lower manual limit and unknown refresh rates`() {
        assertEquals(1_000_000_000L / 30, desktopFramePeriodNanos(30, vsync = true, refreshRate = 144))
        assertEquals(1_000_000_000L / 60, desktopFramePeriodNanos(300, vsync = true, refreshRate = 60))
        assertEquals(1_000_000_000L / 60, desktopFramePeriodNanos(300, vsync = true, refreshRate = 0))
    }

    @Test
    fun `changing the limit changes the next frame interval immediately`() {
        val frameStart = 1_000_000_000L
        val afterRender = frameStart + 2_000_000L
        assertEquals(31_333_333L, desktopNextFrameDelayNanos(frameStart, afterRender, desktopFramePeriodNanos(30, false, 60)))
        assertEquals(1_333_333L, desktopNextFrameDelayNanos(frameStart, afterRender, desktopFramePeriodNanos(300, false, 60)))
    }

    @Test
    fun `slow frames do not cause catch up bursts that exceed the maximum`() {
        val period = desktopFramePeriodNanos(60, false, 60)
        var now = 0L
        val starts = mutableListOf<Long>()
        for (work in listOf(2_000_000L, 100_000_000L, 2_000_000L, 2_000_000L)) {
            val start = now
            starts += start
            now += work
            now += desktopNextFrameDelayNanos(start, now, period)
        }
        assertTrue(starts.zipWithNext().all { (before, after) -> after - before >= period })
    }
}
