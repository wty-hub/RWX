package io.github.rwx.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class SimulationCompatibilityTraceTest {
    private fun state(tick: Int = 1, vararg sections: Pair<String, ByteArray>) =
        SimulationCompatibilityTrace.State(tick, sections.map { SimulationCompatibilityTrace.Section(it.first, it.second) })

    @Test fun `comparison checks complete serialized bytes rather than state hashes`() {
        val first = state(1, "legacy-save" to byteArrayOf(1, 2, 3))
        assertEquals(null, first.firstDifference(state(1, "legacy-save" to byteArrayOf(1, 2, 3))))
        assertEquals("legacy-save byte 2 (sizes 3/3)", first.firstDifference(state(1, "legacy-save" to byteArrayOf(1, 2, 4))))
    }
    @Test fun `tick queue order and truncated state all fail comparison`() {
        val first = state(1, "pending-command-order" to byteArrayOf(1, 2), "simulation-clock" to byteArrayOf(3))
        assertNotNull(first.firstDifference(state(2, "pending-command-order" to byteArrayOf(1, 2), "simulation-clock" to byteArrayOf(3))))
        assertEquals("section order", first.firstDifference(state(1, "simulation-clock" to byteArrayOf(3), "pending-command-order" to byteArrayOf(1, 2))))
        assertNotNull(first.firstDifference(state(1, "pending-command-order" to byteArrayOf(1), "simulation-clock" to byteArrayOf(3))))
    }
}
