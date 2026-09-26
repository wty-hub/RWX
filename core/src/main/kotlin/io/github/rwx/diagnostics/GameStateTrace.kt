package io.github.rwx.diagnostics

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.network.GameStateChecksum
import java.io.BufferedWriter
import java.io.File

/**
 * Per-tick simulation fingerprint for verifying that performance changes stay bit-exact with the
 * original game. Enabled with `RWX_CHECKSUM_LOG=/path/file`, or [start] after argument parsing.
 * Writes one line per simulation tick.
 *
 * Only reads game state. Uses its own [GameStateChecksum] instance (the same computation network sync
 * uses) plus a stricter hash over raw float bits of every orderable unit.
 */
object GameStateTrace {
    private var output: File? = System.getenv("RWX_CHECKSUM_LOG")?.takeIf { it.isNotBlank() }?.let(::File)

    @JvmField
    var enabled: Boolean = output != null

    private val checksum = GameStateChecksum()
    private var writer: BufferedWriter? = null

    /** Switch the checksum log to [file]. Creates parent directories. Replaces `RWX_CHECKSUM_LOG`. */
    @JvmStatic
    fun start(file: File) {
        close()
        file.parentFile?.mkdirs()
        output = file
        enabled = true
    }

    @JvmStatic
    fun close() {
        val out = writer ?: return
        out.flush()
        out.close()
        writer = null
    }

    @JvmStatic
    fun onTickEnd(engine: GameEngine) {
        val file = output ?: return
        val out = writer ?: file.bufferedWriter().also { writer = it }
        checksum.computeChecksums()
        out.append(engine.currentTick.toString())
        out.append(" total=").append(checksum.totalChecksum.toString())
        for (field in checksum.fields) {
            out.append(' ').append(field.label.replace(' ', '_')).append('=').append(field.value.toString())
        }
        out.append(" strict=").append(java.lang.Long.toHexString(strictHash()))
        out.append('\n')
        if (engine.currentTick % FLUSH_INTERVAL_TICKS == 0) out.flush()
    }

    private fun strictHash(): Long {
        var hash = FNV_OFFSET
        var count = 0
        for (gameObject in GameObject.fastGameObjectList) {
            val unit = gameObject as? OrderableUnit ?: continue
            count++
            hash = mix(hash, unit.objectId)
            hash = mix(hash, unit.posX.toRawBits().toLong())
            hash = mix(hash, unit.posY.toRawBits().toLong())
            hash = mix(hash, unit.direction.toRawBits().toLong())
            hash = mix(hash, unit.rotation.toRawBits().toLong())
            hash = mix(hash, unit.rotationSpeed.toRawBits().toLong())
            hash = mix(hash, unit.currentHealth.toRawBits().toLong())
            val waypoint = unit.getCurrentWaypoint()
            if (waypoint != null) {
                hash = mix(hash, waypoint.getCommandTypeOrdinal().toLong())
                hash = mix(hash, waypoint.getTargetX().toRawBits().toLong())
                hash = mix(hash, waypoint.getTargetY().toRawBits().toLong())
            }
        }
        return mix(hash, count.toLong())
    }

    private fun mix(hash: Long, value: Long): Long = (hash xor value) * FNV_PRIME

    private const val FNV_OFFSET = -0x340d631b7bdddcdbL
    private const val FNV_PRIME = 0x100000001b3L
    private const val FLUSH_INTERVAL_TICKS = 600
}
