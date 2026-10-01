package io.github.rwx.kool.vulkan

/**
 * Call [completed] only after the native fence for that slot has signalled, not once per UI frame.
 * Pending callbacks are attached at submission, so failed image acquisition never retires resources.
 */
internal class FrameFenceRetirementQueue(slotCount: Int = 2) {
    private val pending = ArrayList<() -> Unit>()
    private val slots = Array(slotCount) { ArrayList<() -> Unit>() }

    val pendingCount: Int get() = pending.size + slots.sumOf { it.size }

    fun retain(release: () -> Unit) {
        pending += release
    }

    fun retainAfterSubmitted(slot: Int, release: () -> Unit) {
        slots[slot] += release
    }

    fun submitted(slot: Int) {
        check(slots[slot].isEmpty()) { "Submitted a GPU frame without waiting for its previous fence" }
        slots[slot].addAll(pending)
        pending.clear()
    }

    fun completed(slot: Int) {
        val releases = slots[slot].toList()
        slots[slot].clear()
        releases.forEach { it() }
    }

    /** Only legal after vkDeviceWaitIdle, including callbacks whose frame was never submitted. */
    fun deviceIdle() {
        slots.indices.forEach(::completed)
        val releases = pending.toList()
        pending.clear()
        releases.forEach { it() }
    }
}

internal class UploadChunkCursor(val capacity: Int) {
    var used = 0
        private set

    fun allocate(bytes: Int, alignment: Int = 16): Int? {
        require(bytes >= 0 && alignment > 0 && alignment and (alignment - 1) == 0)
        val start = (used.toLong() + alignment - 1) and (alignment.toLong() - 1).inv()
        if (start + bytes > capacity) return null
        used = (start + bytes).toInt()
        return start.toInt()
    }

    fun resetAfterFence() { used = 0 }
}
