package io.github.rwx.benchmark

import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import io.github.rwx.diagnostics.SimulationCompatibilityTrace
import io.github.rwx.kool.EngineOwnerLoop
import kotlinx.serialization.json.*
import java.io.File
import java.util.Base64

/** Prerecorded outer frame intervals and ordered command input, independent of presentation time. */
object DriverParityHarness {
    data class RecordedFrame(val deltaSeconds: Float, val frameMillis: Int,
        val input: List<(GameEngine) -> Unit> = emptyList())
    data class Result(val matched: Boolean, val frames: Int, val ticks: Int, val executedCommands: Int,
        val firstDifference: String?) {
        fun json() = buildJsonObject {
            put("matched", matched); put("frames", frames); put("tickStates", ticks)
            put("executedCommands", executedCommands)
            firstDifference?.let { put("firstDifference", it) }
            put("comparison", "complete legacy save bytes, accumulator/step rate, object and selection order, all command queue bytes/timing, actual execution order")
            put("limitation", "driver handoff correctness; original-client multiplayer and all unit behavior scenarios require separate tests")
        }
    }
    private data class Run(val frames: List<SimulationCompatibilityTrace.State>,
        val ticks: List<SimulationCompatibilityTrace.State>, val commands: List<SimulationCompatibilityTrace.ExecutedCommand>)

    /** reset must restore the same original save, including IDs/RNG and empty input queues. */
    fun compare(tape: List<RecordedFrame>, reset: () -> GameEngine, report: File, mode: String = "single-player"): Result {
        report.parentFile?.mkdirs()
        val inline = run(tape, reset, File(report.path + ".inline.ndjson"))
        val owner = EngineOwnerLoop(periodNanos = { 1_000_000_000L }, tick = {}, onFailure = { throw it })
        val detached = try { owner.call { run(tape, reset, File(report.path + ".owner.ndjson")) } }
            finally { owner.close() }
        var difference: String? = inline.frames.firstOrNull()?.let { first ->
            detached.frames.firstOrNull()?.let(first::firstDifference)?.let { "initial state: $it" }
        }
        fun compareStates(label: String, left: List<SimulationCompatibilityTrace.State>, right: List<SimulationCompatibilityTrace.State>) {
            if (difference != null) return
            if (left.size != right.size) { difference = "$label count ${left.size}/${right.size}"; return }
            for (index in left.indices) left[index].firstDifference(right[index])?.let {
                difference = "$label $index: $it"; return
            }
        }
        compareStates("tick", inline.ticks, detached.ticks)
        compareStates("frame", inline.frames, detached.frames)
        if (difference == null) {
            if (inline.commands.size != detached.commands.size) difference = "executed command count ${inline.commands.size}/${detached.commands.size}"
            else for (index in inline.commands.indices) {
                val left = inline.commands[index]; val right = detached.commands[index]
                if (left.tick != right.tick || left.scheduledTick != right.scheduledTick || left.createdTick != right.createdTick || !left.bytes.contentEquals(right.bytes)) {
                    difference = "executed command $index timing or complete serialized target/queue payload"; break
                }
            }
        }
        return Result(difference == null, tape.size, inline.ticks.size, inline.commands.size, difference)
            .also { report.writeText(JsonObject(it.json() + ("mode" to JsonPrimitive(mode))).toString() + "\n") }
    }

    /** Includes normal queued move/attack-move orders; never samples the selected target IDs. */
    fun defaultTape(frames: Int): List<RecordedFrame> {
        require(frames > 0)
        val intervals = floatArrayOf(1f / 144, 1f / 75, 1f / 60, 1f / 300, .033f, .004f)
        return List(frames) { index ->
            val delta = intervals[index % intervals.size]
            val inputs = if (index % 7 == 0) listOf<(GameEngine) -> Unit>({ engine ->
                val units = GameObject.fastGameObjectList.filterIsInstance<OrderableUnit>()
                    .filter { !it.isDead && !it.isDestroyed && it.moveSpeed > 0f }
                val team = units.firstOrNull()?.team
                if (team != null) {
                    val command = engine.commandController.createCommandForTeam(team)
                    units.filter { it.team === team }.forEach(command::addUnitToCommand)
                    command.isQueued = index % 14 != 0
                    if (index % 14 == 0) command.setAttackMoveTarget(engine.viewpointX + 240f, engine.viewpointY + 180f)
                    else command.setMoveTarget(engine.viewpointX + 260f, engine.viewpointY + 200f)
                }
            }) else emptyList()
            RecordedFrame(delta, (delta * 1000f).toInt(), inputs)
        }
    }

    /** Recording uses real commands, including the original step-rate change system command. */
    fun replayRecordingTape(frames: Int): List<RecordedFrame> = defaultTape(frames).mapIndexed { index, frame ->
        val stepRate = when (index) { 15 -> .5f; 65 -> 1.5f; else -> null }
        if (stepRate == null) frame else frame.copy(input = frame.input + { engine: GameEngine ->
            val team = engine.playerTeam
            engine.commandController.createCommandForTeam(team).apply {
                isSystemAction = true
                gameSpeedChange = stepRate
            }
            Unit
        })
    }

    /** Inputs change playback speed and pause locally; recorded commands come from ReplayEngine. */
    fun replayPlaybackTape(frames: Int): List<RecordedFrame> = defaultTape(frames).mapIndexed { index, frame ->
        val speed = when (index) { 20 -> 0f; 23 -> 1f; 40 -> 2f; 55 -> 1f; else -> null }
        frame.copy(input = if (speed == null) emptyList() else listOf({ engine -> engine.gameSpeed = speed }))
    }

    fun advanceTape(engine: GameEngine, tape: List<RecordedFrame>) {
        for (frame in tape) {
            frame.input.forEach { it(engine) }
            engine.gameLoop(frame.deltaSeconds * 60f, frame.frameMillis)
        }
    }

    private fun run(tape: List<RecordedFrame>, reset: () -> GameEngine, traceFile: File): Run {
        val engine = reset()
        val ticks = mutableListOf<SimulationCompatibilityTrace.State>()
        val commands = mutableListOf<SimulationCompatibilityTrace.ExecutedCommand>()
        val frames = mutableListOf<SimulationCompatibilityTrace.State>()
        SimulationCompatibilityTrace.observe(ticks::add, commands::add)
        try {
            traceFile.bufferedWriter().use { writer ->
                val initial = SimulationCompatibilityTrace.capture(engine)
                frames += initial
                writer.append(buildJsonObject {
                    put("frame", -1); put("tick", initial.tick)
                    put("sections", JsonObject(initial.sections.associate { it.name to JsonPrimitive(Base64.getEncoder().encodeToString(it.bytes)) }))
                }.toString()).append('\n')
                for ((index, recorded) in tape.withIndex()) {
                    for (input in recorded.input) input(engine)
                    engine.gameLoop(recorded.deltaSeconds * 60f, recorded.frameMillis)
                    val state = SimulationCompatibilityTrace.capture(engine)
                    frames += state
                    writer.append(buildJsonObject {
                        put("frame", index); put("tick", state.tick)
                        put("deltaRawBits", recorded.deltaSeconds.toRawBits()); put("frameMillis", recorded.frameMillis)
                        put("sections", JsonObject(state.sections.associate { it.name to JsonPrimitive(Base64.getEncoder().encodeToString(it.bytes)) }))
                    }.toString()).append('\n')
                }
            }
        } finally { SimulationCompatibilityTrace.observe(null, null) }
        return Run(frames, ticks, commands)
    }
}
