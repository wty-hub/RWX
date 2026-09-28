package io.github.rwx.slick

/**
 * A two-slot hand-off from the Slick render thread to Kool. The producer never waits for a
 * texture upload: if Kool is behind, the next capture replaces the unconsumed frame. A slot being
 * uploaded is never reused until the consumer returns it.
 */
internal class SlickCompleteFrameMailbox {
    internal class Slot {
        var width: Int = 0
            private set
        var height: Int = 0
            private set
        var pixels: IntArray = IntArray(0)
            private set
        var sequence: Long = 0L
            internal set
        internal var generation: Long = 0L

        internal fun resize(width: Int, height: Int) {
            val pixelCount = Math.multiplyExact(width, height)
            if (pixels.size != pixelCount) pixels = IntArray(pixelCount)
            this.width = width
            this.height = height
        }
    }

    private val slots = Array(2) { Slot() }
    private var requested = false
    private var writing: Slot? = null
    private var pending: Slot? = null
    private var reading: Slot? = null
    private var generation = 0L
    private var sequence = 0L

    @Synchronized
    fun requestNext() {
        requested = true
    }

    @Synchronized
    fun beginCapture(width: Int, height: Int): Slot? {
        if (!requested || width <= 0 || height <= 0 || writing != null) return null
        val slot = slots.firstOrNull { it !== reading && it !== pending }
            ?: pending?.takeIf { it !== reading }
            ?: return null
        if (slot === pending) pending = null
        slot.resize(width, height)
        slot.generation = generation
        writing = slot
        requested = false
        return slot
    }

    @Synchronized
    fun publish(slot: Slot) {
        if (writing !== slot) return
        writing = null
        if (slot.generation == generation) {
            slot.sequence = ++sequence
            pending = slot
        }
    }

    @Synchronized
    fun abandon(slot: Slot) {
        if (writing === slot) writing = null
    }

    fun consumeLatest(consumer: (Slot) -> Unit): Boolean {
        val slot = synchronized(this) {
            check(reading == null) { "Slick complete frame has multiple consumers" }
            pending?.also {
                pending = null
                reading = it
            }
        } ?: return false
        try {
            consumer(slot)
        } finally {
            synchronized(this) { reading = null }
        }
        return true
    }

    @Synchronized
    fun clear() {
        generation++
        requested = false
        pending = null
    }
}
