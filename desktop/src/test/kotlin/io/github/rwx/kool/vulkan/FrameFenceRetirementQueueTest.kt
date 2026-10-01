package io.github.rwx.kool.vulkan

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FrameFenceRetirementQueueTest {
    @Test
    fun `skipped frames cannot release pending resources`() {
        val queue = FrameFenceRetirementQueue()
        var released = 0
        queue.retain { released++ }
        repeat(100) { queue.completed(it % 2) }
        assertEquals(0, released)
        assertEquals(1, queue.pendingCount)
        queue.submitted(0)
        queue.completed(1)
        assertEquals(0, released)
        queue.completed(0)
        assertEquals(1, released)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `each slot owns its resources until its fence completes`() {
        val queue = FrameFenceRetirementQueue()
        val released = ArrayList<Int>()
        queue.retain { released += 10 }
        queue.submitted(0)
        queue.retain { released += 20 }
        queue.submitted(1)
        assertFailsWith<IllegalStateException> { queue.submitted(0) }
        queue.completed(1)
        assertEquals(listOf(20), released)
        queue.completed(0)
        assertEquals(listOf(20, 10), released)
    }

    @Test
    fun `retiring last submitted frame does not require another submission`() {
        val queue = FrameFenceRetirementQueue()
        var released = 0
        queue.submitted(0)
        queue.retainAfterSubmitted(0) { released++ }
        queue.completed(1)
        assertEquals(0, released)
        queue.completed(0)
        assertEquals(1, released)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `device idle drains submitted and pending callbacks exactly once`() {
        val queue = FrameFenceRetirementQueue()
        var released = 0
        queue.retain { released++ }
        queue.submitted(0)
        queue.retain { released++ }
        queue.deviceIdle()
        queue.deviceIdle()
        assertEquals(2, released)
        assertEquals(0, queue.pendingCount)
    }

    @Test
    fun `upload slices align and never alias until fence reset`() {
        val cursor = UploadChunkCursor(64)
        assertEquals(0, cursor.allocate(3))
        assertEquals(16, cursor.allocate(17))
        assertEquals(48, cursor.allocate(16))
        assertNull(cursor.allocate(1))
        cursor.resetAfterFence()
        assertEquals(0, cursor.allocate(64))
        assertNull(cursor.allocate(1))
    }

    @Test
    fun `upload cursor rejects overflow without changing allocation position`() {
        val cursor = UploadChunkCursor(Int.MAX_VALUE)
        assertEquals(0, cursor.allocate(Int.MAX_VALUE - 7))
        assertNull(cursor.allocate(16))
        assertEquals(Int.MAX_VALUE - 7, cursor.used)
    }
}
