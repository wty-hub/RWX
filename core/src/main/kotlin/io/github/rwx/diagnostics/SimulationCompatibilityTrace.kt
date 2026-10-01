package io.github.rwx.diagnostics

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.gameFramework.Command
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.network.GameOutputStream
import java.io.BufferedWriter
import java.io.File
import java.util.Base64

/**
 * Opt-in correctness trace, separate from performance measurement. Stores complete legacy save
 * serialization plus all command queues/timing and update/selection order, rather than a few floats.
 * RWX_FULL_STATE_LOG can be very large; never enable it for FPS acceptance measurements.
 */
object SimulationCompatibilityTrace {
    data class Section(val name: String, val bytes: ByteArray)
    data class State(val tick: Int, val sections: List<Section>) {
        fun firstDifference(other: State): String? {
            if (tick != other.tick) return "tick: $tick != ${other.tick}"
            if (sections.map { it.name } != other.sections.map { it.name }) return "section order"
            for ((index, section) in sections.withIndex()) {
                val otherBytes = other.sections[index].bytes
                if (!section.bytes.contentEquals(otherBytes)) {
                    val offset = section.bytes.indices.firstOrNull { it >= otherBytes.size || section.bytes[it] != otherBytes[it] }
                        ?: minOf(section.bytes.size, otherBytes.size)
                    return "${section.name} byte $offset (sizes ${section.bytes.size}/${otherBytes.size})"
                }
            }
            return null
        }
    }
    data class ExecutedCommand(val tick: Int, val scheduledTick: Int, val createdTick: Int, val bytes: ByteArray)

    private var file: File? = System.getenv("RWX_FULL_STATE_LOG")?.takeIf(String::isNotBlank)?.let(::File)
    private var writer: BufferedWriter? = null
    private var tickObserver: ((State) -> Unit)? = null
    private var commandObserver: ((ExecutedCommand) -> Unit)? = null
    val enabled: Boolean get() = file != null || tickObserver != null || commandObserver != null
    private val accumulatorField by lazy { GameLogic::class.java.getDeclaredField("accumulator").apply { isAccessible = true } }

    /** Install/remove only on the engine owner, with no gameLoop in progress. */
    fun observe(ticks: ((State) -> Unit)?, commands: ((ExecutedCommand) -> Unit)?) {
        tickObserver = ticks; commandObserver = commands
        GameStateTrace.refreshEnabled()
    }

    fun start(output: File) {
        close()
        file = output
        output.parentFile?.mkdirs()
        GameStateTrace.refreshEnabled()
    }

    fun close() {
        writer?.close(); writer = null
        file = null
        GameStateTrace.refreshEnabled()
    }

    @JvmStatic
    fun onCommandExecuted(engine: GameEngine, command: Command) {
        if (!enabled) return
        val output = GameOutputStream()
        command.serializeCommand(output)
        val executed = ExecutedCommand(engine.currentTick, command.scheduledTick, command.createdTick, output.toByteArray())
        commandObserver?.invoke(executed)
        writer()?.apply {
            append("{\"kind\":\"command\",\"tick\":${executed.tick},\"scheduledTick\":${executed.scheduledTick},")
            append("\"createdTick\":${executed.createdTick},\"serialized\":\"${encode(executed.bytes)}\"}\n")
        }
    }

    fun onTickEnd(engine: GameEngine) {
        if (file == null && tickObserver == null) return
        val state = capture(engine)
        tickObserver?.invoke(state)
        writer()?.apply {
            append("{\"kind\":\"tick\",\"tick\":${state.tick},\"sections\":{")
            state.sections.forEachIndexed { index, section ->
                if (index != 0) append(',')
                append('"').append(section.name).append("\":\"").append(encode(section.bytes)).append('"')
            }
            append("}}\n")
            if (state.tick % 60 == 0) flush()
        }
    }

    /** Capture on the sole engine owner; serializers can observe the full legacy command state. */
    fun capture(engine: GameEngine): State {
        val sections = mutableListOf<Section>()
        fun section(name: String, block: (GameOutputStream) -> Unit) {
            val stream = GameOutputStream()
            block(stream)
            sections += Section(name, stream.toByteArray())
        }
        for (obj in GameObject.fastGameObjectList) {
            val gameObject = obj as GameObject
            section("object-${gameObject.objectId}") { gameObject.a(it) }
        }
        section("legacy-save") { engine.gameSaver.writeSaveToStream(it) }
        section("simulation-clock") {
            it.writeInt(engine.currentTick); it.writeInt(engine.gameTimeMillis)
            // The normal single-player save loader reinitializes this map RNG seed; it is not
            // included in the legacy save payload but directly controls deterministic aiming.
            it.writeInt(engine.globalSeed)
            it.writeInt(engine.networkEngine.currentStepRate.toRawBits())
            it.writeInt(if (engine is GameLogic) accumulatorField.getFloat(engine).toRawBits() else 0)
            it.writeInt(engine.networkEngine.nextBlockingFrame)
            it.writeInt(engine.networkEngine.commandFrameInterval)
        }
        section("object-update-order") { out ->
            out.writeInt(GameObject.fastGameObjectList.size)
            for (obj in GameObject.fastGameObjectList) out.writeLong((obj as GameObject).objectId)
        }
        section("selection-order") { out ->
            val selected = engine.gameUI.selectedUnitsList
            out.writeInt(selected.size)
            for (unit in selected) out.writeLong((unit as BaseUnit).objectId)
        }
        fun commands(name: String, commands: List<Command>) = section(name) { out ->
            out.writeInt(commands.size)
            for (command in commands) {
                out.writeInt(command.scheduledTick); out.writeInt(command.createdTick)
                out.writeBoolean(command.isReplayCommand); out.writeBoolean(command.hasProcessedTargets)
                command.serializeCommand(out)
            }
        }
        commands("pending-command-order", engine.commandController.pendingCommands)
        commands("queued-command-order", engine.commandController.queuedCommands)
        commands("executed-command-order", engine.commandController.executedCommands)
        return State(engine.currentTick, sections)
    }

    private fun writer(): BufferedWriter? = file?.let { output ->
        writer ?: run { output.parentFile?.mkdirs(); output.bufferedWriter().also { writer = it } }
    }
    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
}
