package io.github.rwx.benchmark

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.ReplayTimelineIndex
import com.corrodinggames.rts.gameFramework.file.FileHelper
import com.corrodinggames.rts.gameFramework.network.GameOutputStream
import io.github.rwx.diagnostics.SimulationCompatibilityTrace
import io.github.rwx.headless.HeadlessGameSession
import io.github.rwx.render.canvas.*
import com.corrodinggames.rts.gameFramework.ui.GameInterfaceRenderer
import kotlinx.serialization.json.*
import java.io.File
import java.security.MessageDigest

/** Real replay simulation acceptance, run with RWX_REPLAY_SEEK_OUTPUT and desktop:headless. */
object ReplaySeekAcceptanceHarness {
    private data class State(val tick: Int, val time: Int, val digest: String, val sections: Map<String, String>)

    private fun capture(engine: GameEngine): State {
        val digest = MessageDigest.getInstance("SHA-256")
        val sections = linkedMapOf<String, String>()
        fun section(name: String, bytes: ByteArray) {
            digest.update(bytes)
            sections[name] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
        val trace = SimulationCompatibilityTrace.capture(engine)
        trace.sections.filter { it.name.startsWith("object-") || it.name.endsWith("command-order") }
            .forEach { section(it.name, it.bytes) }
        for (id in 0 until PlayerTeam.TEAM_NEUTRAL) PlayerTeam.k(id)?.let { team ->
            val stream = GameOutputStream()
            val pingTime = team.teamLastPingTime
            try {
                // Loaders replace this wall-clock connection timestamp; it is not simulation state.
                team.teamLastPingTime = 0
                team.a(stream) // includes resources and complete fog state
            } finally { team.teamLastPingTime = pingTime }
            section("team-$id", stream.toByteArray())
        }
        digest.update(engine.globalSeed.toString().toByteArray())
        digest.update(engine.networkEngine.currentStepRate.toRawBits().toString().toByteArray())
        return State(engine.currentTick, engine.gameTimeMillis,
            digest.digest().joinToString("") { "%02x".format(it) }, sections)
    }

    fun run(session: HeadlessGameSession, engine: GameEngine, name: String, output: File): Boolean {
        val cases = mutableListOf<JsonObject>()
        fun waitIndex() {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (engine.replayEngine.isIndexing && System.nanoTime() < deadline) Thread.sleep(5)
            check(engine.replayEngine.durationMillis >= 0) { engine.replayEngine.timelineError ?: "Index timeout" }
        }
        fun normalUntil(target: Int) {
            val deadline = System.nanoTime() + 120_000_000_000L
            while (engine.gameTimeMillis < target && !engine.replayEngine.isPlaybackEnded) {
                check(System.nanoTime() < deadline) { "Playback timeout at ${engine.gameTimeMillis}" }
                engine.gameLoop(engine.networkEngine.currentStepRate + .001f, 16)
            }
        }
        fun seek(target: Int) {
            engine.replayEngine.requestSeek(target)
            val deadline = System.nanoTime() + 120_000_000_000L
            while (engine.replayEngine.isSeeking) {
                check(System.nanoTime() < deadline) { "Seek timeout at ${engine.gameTimeMillis}" }
                engine.gameLoop(1f, 16)
            }
            check(engine.replayEngine.seekError == null) { engine.replayEngine.seekError ?: "Seek failed" }
        }
        var failure: String? = null
        try {
            waitIndex()
            val duration = engine.replayEngine.durationMillis
            val index = FileHelper.openFile(engine.replayEngine.a(name, false)).use { ReplayTimelineIndex.scan(it) { false } }
            val start = engine.replayEngine.startTimeMillis
            val targets = (listOf(start + 15_000, start + 35_000, start + 65_000) + index.checkpoints.map { it.timeMillis() + 2000 })
                .distinct().sorted().filter { it + 1000 < duration }
            check(targets.isNotEmpty()) { "Replay too short for acceptance" }
            val baseline = mutableMapOf<Int, State>()
            val continuation = mutableMapOf<Int, State>()
            for (target in targets) {
                normalUntil(target)
                baseline[target] = capture(engine)
                normalUntil(target + 1000)
                continuation[target] = capture(engine)
            }
            session.openReplay(name)
            waitIndex()
            for (target in targets.reversed()) {
                engine.gameSpeed = 0f
                engine.gameLoop(0f, 16) // settle the loader viewport without advancing simulation
                val cameraX = engine.viewpointX
                val cameraY = engine.viewpointY
                seek(target)
                val actual = capture(engine)
                val matched = actual == baseline[target]
                cases += buildJsonObject {
                    put("targetMillis", target); put("matchesContinuous", matched)
                    put("expectedTick", baseline[target]!!.tick); put("actualTick", actual.tick)
                    put("expectedDigest", baseline[target]!!.digest); put("actualDigest", actual.digest)
                }
                check(matched) { "Seek state differs at $target: " + baseline[target]!!.sections.keys
                    .filter { baseline[target]!!.sections[it] != actual.sections[it] }.joinToString() }
                check(engine.gameSpeed == 0f) { "Paused playback resumed after seek" }
                check(engine.viewpointX == cameraX && engine.viewpointY == cameraY) { "Seek moved camera: $cameraX,$cameraY / ${engine.viewpointX},${engine.viewpointY}" }
                engine.gameSpeed = 1f
                normalUntil(target + 1000)
                check(capture(engine) == continuation[target]) { "Continuation differs after $target" }
            }
            engine.gameSpeed = 4f
            engine.replayEngine.requestSeek(start + 15_000)
            engine.replayEngine.requestSeek(start + 35_000) // latest request replaces the old target
            while (engine.replayEngine.isSeeking) engine.gameLoop(1f, 16)
            check(engine.gameSpeed == 4f)
            check(capture(engine) == baseline[start + 35_000])
            seek(0)
            check(engine.gameTimeMillis <= start + engine.networkEngine.currentStepRate * 17 + 1)
            cases += buildJsonObject { put("startBoundary", true); put("replacementAndSpeed", true) }
            // Reach the actual EOF, then check that simulation freezes and the timeline survives.
            engine.gameSpeed = 1f
            seek(Int.MAX_VALUE)
            repeat(3) { engine.gameLoop(1f, 16) }
            check(engine.replayEngine.isPlaybackEnded)
            val end = capture(engine)
            repeat(3) { engine.gameLoop(1f, 16) }
            check(end == capture(engine)) { "Simulation continued after replay EOF" }
            check(engine.replayEngine.j() && engine.gameSpeed == 0f)
            seek(targets.first())
            check(capture(engine) == baseline[targets.first()])
            cases += buildJsonObject { put("endBoundaryAndRewind", true) }
            // Exercise the shared HUD through the same canvas command stream used by desktop backends.
            val oldGraphics = engine.renderGraphicsEngine
            val oldWidth = engine.screenWidth.toInt()
            val oldHeight = engine.screenHeight.toInt()
            val hudMethod = GameInterfaceRenderer::class.java.getDeclaredMethod("a", Float::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val hudSizes = listOf(320 to 240, 1280 to 720, 1920 to 1080)
            try {
                val graphics = KoolGraphicsEngine(KoolCanvasCpuTextureStore())
                engine.renderGraphicsEngine = graphics
                for ((width, height) in hudSizes) {
                    engine.updateWindowResolution(width, height)
                    graphics.beginFrame(width, height)
                    hudMethod.invoke(engine.gameUI.interfaceRenderer, 0f, true)
                    val frame = graphics.snapshot()
                    val texts = frame.commands.filterIsInstance<KoolCanvasCommand.DrawText>()
                    check(texts.any { it.text == "−10s" } && texts.any { it.text == "+10s" })
                    check(texts.any { " / " in it.text })
                    check(frame.commands.filterIsInstance<KoolCanvasCommand.DrawRect>().all {
                        it.rect.left.isFinite() && it.rect.right.isFinite() && it.rect.top.isFinite() && it.rect.bottom.isFinite()
                    })
                }
            } finally {
                engine.renderGraphicsEngine = oldGraphics
                engine.updateWindowResolution(oldWidth, oldHeight)
            }
            cases += buildJsonObject { put("sharedHudRenderSizes", JsonArray(hudSizes.map { JsonPrimitive("${it.first}x${it.second}") })) }
            // Check the ordinary save round trip before allowing synthetic replay checkpoints.
            // Even with clock/RNG/rate sidebands restored, the object serialization must be exact.
            val snapshotState = capture(engine)
            val snapshot = checkNotNull(session.captureMapSnapshot())
            val seed = engine.globalSeed
            val rate = engine.networkEngine.currentStepRate
            session.restoreSnapshot(snapshot)
            engine.currentTick = snapshotState.tick
            engine.gameTimeMillis = snapshotState.time
            engine.globalSeed = seed
            engine.networkEngine.applyChangedSetup(rate, "snapshot-cache-probe")
            val restoredState = capture(engine)
            engine.gameSpeed = 1f
            normalUntil(targets.first() + 1000)
            val snapshotContinuationMatches = capture(engine) == continuation[targets.first()]
            cases += buildJsonObject {
                put("syntheticSnapshotRoundTripMatches", snapshotState == restoredState)
                put("syntheticSnapshotContinuationMatches", snapshotContinuationMatches)
                put("syntheticCacheEnabled", false)
                put("differentSnapshotSections", JsonArray(snapshotState.sections.keys
                    .filter { snapshotState.sections[it] != restoredState.sections[it] }.map(::JsonPrimitive)))
            }
            session.openReplay(name)
            waitIndex()
            engine.replayEngine.requestSeek(duration)
            engine.replayEngine.e()
            check(!engine.replayEngine.isSeeking)
            cases += buildJsonObject { put("exitCancelsSeek", true) }
        } catch (error: Throwable) {
            failure = error.toString()
            error.printStackTrace()
        }
        output.parentFile?.mkdirs()
        output.writeText(buildJsonObject {
            put("replay", name); put("passed", failure == null)
            put("cases", JsonArray(cases)); failure?.let { put("error", it) }
        }.toString() + "\n")
        return failure == null
    }
}
