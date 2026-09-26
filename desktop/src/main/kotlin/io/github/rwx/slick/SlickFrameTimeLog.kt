package io.github.rwx.slick

import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.PerformanceProfiler
import io.github.rwx.logger
import java.io.File

/**
 * Frame-time statistics for performance work, enabled with `RWX_PERF_LOG=1` (log) or
 * `RWX_PERF_LOG=/path/file` (append to a file). Runs on the Slick render thread only.
 *
 * Each frame is split into: waiting for the canvas lock, game work (update + draw), buffer swap and
 * frame-rate sync, so stalls outside the game's own work are visible.
 */
internal class SlickFrameTimeLog private constructor(private val output: File?) {
    private val intervals = LongArray(MAX_SAMPLES)
    private val lockWaits = LongArray(MAX_SAMPLES)
    private val works = LongArray(MAX_SAMPLES)
    private val updates = LongArray(MAX_SAMPLES)
    private val draws = LongArray(MAX_SAMPLES)
    private val swaps = LongArray(MAX_SAMPLES)
    private val syncs = LongArray(MAX_SAMPLES)
    private var count = 0
    private var lastFrameStart = 0L
    private var phaseStart = 0L
    private var windowStart = System.nanoTime()

    init {
        PerformanceProfiler.frameTimingEnabled = true
    }

    /** Called just before asking for the canvas lock. */
    fun beginFrame() {
        val now = System.nanoTime()
        if (lastFrameStart != 0L && count < MAX_SAMPLES) {
            intervals[count] = now - lastFrameStart
        }
        lastFrameStart = now
        phaseStart = now
    }

    fun beginWork() {
        lockWaits[slot()] = lap()
    }

    fun endWork() {
        val slot = slot()
        works[slot] = lap()
        val profiler = GameEngine.getInstance()?.performanceProfiler
        if (profiler != null) {
            updates[slot] = profiler.updateNanos
            draws[slot] = profiler.drawNanos
            profiler.takeFrameTimings()
        }
    }

    fun endSwap() {
        swaps[slot()] = lap()
    }

    fun endFrame() {
        syncs[slot()] = lap()
        if (count < MAX_SAMPLES) count++
        val now = System.nanoTime()
        if (now - windowStart >= WINDOW_NANOS) {
            report(now)
        }
    }

    private fun slot(): Int = count.coerceAtMost(MAX_SAMPLES - 1)

    private fun lap(): Long {
        val now = System.nanoTime()
        val elapsed = now - phaseStart
        phaseStart = now
        return elapsed
    }

    private fun report(now: Long) {
        val seconds = (now - windowStart) / 1e9
        val engine = GameEngine.getInstance()
        val line = buildString {
            append("frames=").append(count)
            append(" fps=").append("%.1f".format(count / seconds))
            append(" tick=").append(engine?.currentTick ?: -1)
            append(" units=").append(BaseUnit.bE.size)
            append(" interval[").append(stats(intervals, count - 1)).append(']')
            append(" lock[").append(stats(lockWaits, count)).append(']')
            append(" work[").append(stats(works, count)).append(']')
            append(" update[").append(stats(updates, count)).append(']')
            append(" draw[").append(stats(draws, count)).append(']')
            append(" swap[").append(stats(swaps, count)).append(']')
            append(" sync[").append(stats(syncs, count)).append(']')
        }
        val file = output
        if (file != null) {
            runCatching { file.appendText("${System.currentTimeMillis()} $line\n") }
        } else {
            logger.info("RWXPerf") { line }
        }
        count = 0
        windowStart = now
    }

    private fun stats(values: LongArray, n: Int): String {
        if (n <= 0) return "-"
        val sorted = values.copyOf(n).also { it.sort() }
        fun ms(nanos: Long) = "%.2f".format(nanos / 1e6)
        fun pct(p: Double) = sorted[((n - 1) * p).toInt()]
        return "avg=${ms(sorted.sum() / n)} p50=${ms(pct(0.5))} p95=${ms(pct(0.95))} p99=${ms(pct(0.99))} max=${ms(sorted[n - 1])}"
    }

    companion object {
        private const val MAX_SAMPLES = 4096
        private const val WINDOW_NANOS = 5_000_000_000L

        fun fromEnvironment(): SlickFrameTimeLog? {
            val value = System.getenv("RWX_PERF_LOG")?.takeIf { it.isNotBlank() } ?: return null
            val file = value.takeUnless { it == "1" || it.equals("true", ignoreCase = true) }?.let(::File)
            return SlickFrameTimeLog(file)
        }
    }
}
