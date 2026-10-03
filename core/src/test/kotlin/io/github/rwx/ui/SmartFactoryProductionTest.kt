package io.github.rwx.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class SmartFactoryProductionTest {
    @Test
    fun `all factories receive one order and shortest queues spend first`() {
        val slots = chooseFactorySlots(
            candidates = listOf(
                factory(id = 1, idle = false, total = 5),
                factory(id = 2, idle = false, total = 1),
                factory(id = 3, idle = true, total = 0),
            ),
            count = 1,
            cancel = false,
            singleUnit = false,
        )

        assertEquals(listOf(2, 1, 0), slots)
    }

    @Test
    fun `all factories get each batch round before another round starts`() {
        val slots = chooseFactorySlots(
            candidates = listOf(
                factory(id = 9, idle = false, total = 2),
                factory(id = 4, idle = false, total = 2),
                factory(id = 3, idle = true, total = 0),
            ),
            count = 2,
            cancel = false,
            singleUnit = false,
        )

        assertEquals(listOf(2, 1, 0, 2, 1, 0), slots)
    }

    @Test
    fun `all factories cancel one unit per factory per round`() {
        val slots = chooseFactorySlots(
            candidates = listOf(
                factory(id = 1, idle = false, total = 4),
                factory(id = 2, idle = false, total = 1),
            ),
            count = 1,
            cancel = true,
            singleUnit = false,
        )

        assertEquals(listOf(1, 0), slots)
    }

    @Test
    fun `an idle factory is used before a busy one`() {
        val slots = chooseFactorySlots(
            candidates = listOf(
                factory(id = 1, idle = false, total = 3),
                factory(id = 2, idle = true, total = 0),
            ),
            count = 1,
            cancel = false,
        )

        assertEquals(listOf(1), slots)
    }

    @Test
    fun `a batch fills idle factories before stacking on one`() {
        val slots = chooseFactorySlots(
            candidates = listOf(
                factory(id = 1, idle = true, total = 0),
                factory(id = 2, idle = true, total = 0),
                factory(id = 3, idle = false, total = 4),
            ),
            count = 5,
            cancel = false,
        )

        assertEquals(listOf(0, 1, 0, 1, 0), slots)
    }

    @Test
    fun `equal queues break the tie with the lower unit id`() {
        val slots = chooseFactorySlots(
            candidates = listOf(
                factory(id = 9, idle = false, total = 2),
                factory(id = 4, idle = false, total = 2),
            ),
            count = 1,
            cancel = false,
        )

        assertEquals(listOf(1), slots)
    }

    @Test
    fun `cancel removes from the factory with the most of that unit`() {
        val slots = chooseFactorySlots(
            candidates = listOf(
                factory(id = 1, idle = false, total = 2, action = 1),
                factory(id = 2, idle = false, total = 6, action = 4),
            ),
            count = 3,
            cancel = true,
        )

        assertEquals(listOf(1, 1, 1), slots)
    }

    @Test
    fun `cancel ties break toward the lower unit id`() {
        val slots = chooseFactorySlots(
            candidates = listOf(
                factory(id = 8, idle = false, total = 2, action = 2),
                factory(id = 3, idle = false, total = 2, action = 2),
            ),
            count = 1,
            cancel = true,
        )

        assertEquals(listOf(1), slots)
    }

    private fun factory(id: Long, idle: Boolean, total: Int, action: Int = total) =
        FactoryProductionCandidate(
            id = id,
            idle = idle,
            totalQueue = total,
            actionQueue = action,
        )
}
