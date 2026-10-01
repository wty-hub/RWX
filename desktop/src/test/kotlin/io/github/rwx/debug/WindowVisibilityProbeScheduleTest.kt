package io.github.rwx.debug

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowVisibilityProbeScheduleTest {
    private class Harness {
        val timers = ArrayDeque<Pair<Long, () -> Unit>>()
        val owner = ArrayDeque<() -> Unit>()
        val applied = mutableListOf<Boolean>()
        val requested = mutableListOf<Boolean>()
        var closed = false
        var cancelled = false
        val schedule = WindowVisibilityProbeSchedule(
            after = { millis, task -> timers.addLast(millis to task) },
            onWindowOwner = { owner.addLast(it) },
            setVisible = { applied += it },
            isClosed = { closed },
            cancel = { cancelled = true },
            requested = { requested += it },
        )
    }

    @Test fun restoreIsTimedFromAppliedHideAndDoesNotNeedRenderCallbacks() {
        val h = Harness()
        h.schedule.start(15000, 10000)
        assertEquals(15000L, h.timers.first().first)
        h.timers.removeFirst().second()
        assertEquals(listOf(false), h.requested)
        assertTrue(h.timers.isEmpty()) // A blocked owner cannot produce a false ten-second hide.
        assertTrue(h.applied.isEmpty())
        h.owner.removeFirst()()
        assertEquals(listOf(false), h.applied)
        assertEquals(10000L, h.timers.first().first)
        h.timers.removeFirst().second()
        assertEquals(listOf(false, true), h.requested)
        assertFalse(h.cancelled) // The restore owner callback must actually apply first.
        h.owner.removeFirst()()
        assertEquals(listOf(false, true), h.applied)
        assertTrue(h.cancelled)
    }

    @Test fun closeBeforeOwnerHideCancelsWithoutHiding() {
        val h = Harness()
        h.schedule.start(1, 1)
        h.timers.removeFirst().second()
        h.closed = true
        h.owner.removeFirst()()
        assertTrue(h.applied.isEmpty())
        assertTrue(h.timers.isEmpty())
        assertTrue(h.cancelled)
    }

    @Test fun closeBeforeRestoreDoesNotReshowClosedWindow() {
        val h = Harness()
        h.schedule.start(1, 1)
        h.timers.removeFirst().second()
        h.owner.removeFirst()()
        h.timers.removeFirst().second()
        h.closed = true
        h.owner.removeFirst()()
        assertEquals(listOf(false), h.applied)
        assertTrue(h.cancelled)
    }

    @Test fun nonpositiveDurationsAreRejected() {
        val h = Harness()
        assertFailsWith<IllegalArgumentException> { h.schedule.start(0, 1) }
        assertFailsWith<IllegalArgumentException> { h.schedule.start(1, -1) }
    }
}
