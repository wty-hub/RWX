package io.github.rwx.compatibility

import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.github.rwx.benchmark.VanillaUnitCatalog
import io.github.rwx.diagnostics.SimulationCompatibilityTrace
import io.github.rwx.logger
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameSession
import kotlinx.serialization.json.*
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong

/** Opt-in loopback test control. No live engine reads or mutations occur on the HTTP thread. */
internal object GameCompatibilityProbe {
    fun install(session: GameSession, environment: Map<String, String> = System.getenv()): AutoCloseable? {
        val port = configuredPort(environment) ?: return null
        check(environment["RWX_BENCHMARK_UNITS"].isNullOrBlank()) { "Compatibility and performance probes require separate processes" }
        check(environment["RWX_FULL_STATE_LOG"].isNullOrBlank()) { "Compatibility probe owns the command-only trace observer" }
        val state = OwnerState(session)
        return LoopbackServer(port, { request -> session.submitSessionTask { state.handle(request) } }, {
            session.submitSessionTask { state.close() }
        }).also { logger.info { "RWX compatibility probe listening on 127.0.0.1:${it.port}" } }
    }

    fun configuredPort(environment: Map<String, String>): Int? {
        val value = environment["RWX_COMPAT_PROBE_PORT"]?.takeIf(String::isNotBlank) ?: return null
        return value.toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: throw IllegalArgumentException("RWX_COMPAT_PROBE_PORT must be between 1024 and 65535")
    }

    /** Pure validation, also used before any connection attempt can be enqueued. */
    fun loopbackAddress(value: String): String {
        val match = Regex("^(127\\.0\\.0\\.1|localhost):([0-9]+)$").matchEntire(value)
            ?: throw IllegalArgumentException("The compatibility probe only connects to localhost:port")
        val port = match.groupValues[2].toIntOrNull()?.takeIf { it in 1024..65535 }
            ?: throw IllegalArgumentException("Invalid loopback game port")
        return "127.0.0.1:$port"
    }

    internal class LoopbackServer(
        port: Int,
        private val dispatch: (JsonObject) -> CompletableFuture<JsonObject>,
        private val onClose: () -> Unit = {},
    ) : AutoCloseable {
        private val requestSequence = AtomicLong()
        private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "RWX-compatibility-http").apply { isDaemon = true } }
        private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0)
        val port: Int get() = server.address.port

        init {
            server.executor = executor
            server.createContext("/probe", ::handle)
            server.start()
        }

        private fun handle(exchange: HttpExchange) {
            val requestId = requestSequence.incrementAndGet()
            try {
                require(exchange.remoteAddress.address.isLoopbackAddress) { "Loopback access required" }
                require(exchange.requestURI.path == "/probe" && exchange.requestMethod == "POST") { "Use POST /probe" }
                val bytes = exchange.requestBody.readNBytes(16_385)
                require(bytes.size <= 16_384) { "Request exceeds 16 KiB" }
                val request = Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
                // Timeout is reported as pending/unknown, never as a false operation result.
                val result = dispatch(request).get(120, TimeUnit.SECONDS)
                respond(exchange, 200, buildJsonObject { put("ok", true); put("requestId", requestId); put("data", result) })
            } catch (error: Throwable) {
                val failure = if (error is java.util.concurrent.ExecutionException) error.cause ?: error else error
                respond(exchange, when (failure) { is IllegalArgumentException -> 400; is TimeoutException -> 504; else -> 409 },
                    buildJsonObject {
                        put("ok", false); put("requestId", requestId)
                        put("error", failure.message ?: failure.javaClass.simpleName)
                        put("operationOutcome", if (failure is TimeoutException) "pending-or-unknown" else "failed")
                    })
            } finally { exchange.close() }
        }

        private fun respond(exchange: HttpExchange, status: Int, value: JsonObject) {
            val bytes = value.toString().toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.set("Cache-Control", "no-store")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.write(bytes)
        }

        override fun close() { server.stop(0); executor.shutdownNow(); onClose() }
    }

    private class OwnerState(private val session: GameSession) {
        private var observing = false
        private var executionSequence = 0L
        private var submittedSequence = 0L
        private val executed = ArrayDeque<JsonObject>()

        fun handle(request: JsonObject): JsonObject {
            ensureObservation()
            val operation = request.string("op")
            when (operation) {
                "preload" -> session.preload(KoolCanvasViewport(1920, 1080))
                "host" -> {
                    val map = request.string("map")
                    require(map.startsWith("maps/") || map.startsWith("/SD/")) { "Use a vanilla asset map or /SD/ isolated map fixture" }
                    val port = request.integer("port")
                    require(port in 1024..65535) { "Invalid game port" }
                    val engine = session.preload(KoolCanvasViewport(1920, 1080))
                    requireVanilla(engine)
                    engine.settingsEngine.networkPort = port
                    engine.settingsEngine.udpInMultiplayer = false
                    check(session.hostBattleRoom(map, isPublic = false, useMods = false, rwxP2PSession = false)) { "Host request rejected" }
                }
                "join" -> {
                    val address = loopbackAddress(request.string("address"))
                    val engine = session.preload(KoolCanvasViewport(1920, 1080))
                    requireVanilla(engine)
                    engine.settingsEngine.udpInMultiplayer = false
                    check(session.joinBattleRoom(address, null, false)) { "Join request rejected" }
                }
                "start" -> {
                    requireVanilla(requireEngine())
                    check(session.startBattleRoom()) { "Start request rejected" }
                    if (requireEngine().networkEngine.gameHasBeenStarted) {
                        check(session.adoptStartedGameFromEngine(KoolCanvasViewport(1920, 1080))) { "Started game adoption rejected" }
                    }
                }
                "adopt" -> check(session.adoptStartedGameFromEngine(KoolCanvasViewport(1920, 1080))) { "Started game adoption rejected" }
                "move" -> return move(request)
                "disconnect" -> requireEngine().networkEngine.disconnectNetworking("compatibility test complete")
                "catalog", "status" -> Unit
                else -> throw IllegalArgumentException("Unsupported operation: $operation")
            }
            ensureObservation()
            return if (operation == "catalog") VanillaUnitCatalog.report() else status(request["sinceExecution"]?.jsonPrimitive?.longOrNull ?: 0)
        }

        private fun ensureObservation() {
            if (GameEngine.getInstance() != null && !observing) {
                SimulationCompatibilityTrace.observe(null) { command ->
                    val bytes = command.bytes
                    executed.addLast(buildJsonObject {
                        put("sequence", ++executionSequence); put("tick", command.tick)
                        put("scheduledTick", command.scheduledTick); put("createdTick", command.createdTick)
                        put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
                        put("serialized", Base64.getEncoder().encodeToString(bytes))
                    })
                    while (executed.size > 256) executed.removeFirst()
                }
                observing = true
            }
        }

        private fun move(request: JsonObject): JsonObject {
            val engine = requireEngine()
            requireVanilla(engine)
            val network = engine.networkEngine
            check(network.networkGameActive && network.gameHasBeenStarted && engine.hasLoadedLevel) { "Match is not running" }
            val team = checkNotNull(network.localPlayerTeam) { "Local player is unavailable" }
            val x = request.number("x"); val y = request.number("y")
            require(x.isFinite() && y.isFinite() && x >= 0f && y >= 0f && x <= engine.tileMap.worldWidth && y <= engine.tileMap.worldHeight) { "Move target is outside the map" }
            val units = GameObject.fastGameObjectList.filterIsInstance<OrderableUnit>()
                .filter { !it.isDead && !it.isDestroyed && it.team === team && it.moveSpeed > 0f }
            check(units.isNotEmpty()) { "Local player has no living movable units" }
            val command = engine.commandController.createCommandForTeam(team)
            units.forEach(command::addUnitToCommand)
            val attackMove = request["attackMove"]?.jsonPrimitive?.booleanOrNull == true
            if (attackMove) command.setAttackMoveTarget(x, y) else command.setMoveTarget(x, y)
            return buildJsonObject {
                put("sequence", ++submittedSequence); put("submittedTick", engine.currentTick)
                put("team", team.teamId); put("kind", if (attackMove) "attackMove" else "move")
                put("x", x); put("y", y); put("unitIds", JsonArray(units.map { JsonPrimitive(it.objectId) }))
                put("queue", "original CommandController"); put("queuedCommands", engine.commandController.queuedCommands.size)
            }
        }

        private fun status(sinceExecution: Long): JsonObject = buildJsonObject {
            val engine = GameEngine.getInstance()
            put("engineReady", engine != null)
            if (engine == null) return@buildJsonObject
            val network = engine.networkEngine
            val units = GameObject.fastGameObjectList.filterIsInstance<BaseUnit>().filter { !it.isDead && !it.isDestroyed }
            put("version", engine.getVersionCode(true)); put("tick", engine.currentTick)
            put("ownerMonotonicNs", System.nanoTime())
            put("gameTimeMillis", engine.gameTimeMillis); put("mapLoaded", engine.hasLoadedLevel)
            put("map", engine.currentMapPath ?: ""); put("runningMap", session.runningMapPath() ?: "")
            put("networkActive", network.networkGameActive); put("started", network.gameHasBeenStarted)
            put("host", network.isServer); put("localTeam", network.localPlayerTeam?.teamId ?: -1)
            put("humanPlayers", network.humanPlayerCount); put("unitCount", units.size)
            put("mapWidth", engine.tileMap?.worldWidth ?: 0); put("mapHeight", engine.tileMap?.worldHeight ?: 0)
            put("stepRate", network.currentStepRate); put("commandFrameInterval", network.commandFrameInterval)
            put("checksumIntervalFrames", network.checksumIntervalFrames); put("lastSyncedTick", network.lastSyncedTick)
            put("desyncErrors", network.desyncCount); put("desyncPasses", network.desyncPassCount)
            put("resyncs", network.resyncSendOrReceiveCount); put("blocked", network.frameUpdateBlocked)
            put("paused", engine.isPaused || network.gamePaused); put("pausedOnDesync", network.pausedOnDesync)
            put("joining", session.isJoiningBattleRoom); session.latestBattleRoomJoinError?.let { put("joinError", it) }
            put("pendingCommands", engine.commandController.pendingCommands.size)
            put("queuedCommands", engine.commandController.queuedCommands.size)
            put("executedQueue", engine.commandController.executedCommands.size)
            put("submittedCommands", submittedSequence); put("executionSequence", executionSequence)
            put("commands", JsonArray(executed.filter { it["sequence"]!!.jsonPrimitive.long > sinceExecution }))
            put("peers", JsonArray(network.sendQueue.map { peer -> buildJsonObject {
                put("id", peer.connectionId); put("connected", peer.isConnected)
                put("team", peer.player?.teamId ?: -1); put("syncMatches", peer.syncMatchCount)
                put("desyncErrors", peer.desyncCount); put("commands", peer.commandCounter)
            } }))
            put("unitTypes", JsonObject(units.groupingBy { it.unitType?.unitTypeDescriptionShort ?: "unknown" }.eachCount().mapValues { JsonPrimitive(it.value) }))
        }

        private fun requireEngine() = checkNotNull(GameEngine.getInstance()) { "Engine is not ready" }
        private fun requireVanilla(engine: GameEngine) {
            check(!engine.networkEngine.p2pSession) { "P2P is outside original 1.15 acceptance" }
            check(CustomUnitConfig.activeConfigs.none { it.modInfo != null }) { "External unit mods must be disabled" }
        }
        fun close() { if (observing) SimulationCompatibilityTrace.observe(null, null); observing = false }
    }

    private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: throw IllegalArgumentException("Missing string: $key")
    private fun JsonObject.integer(key: String) = this[key]?.jsonPrimitive?.intOrNull
        ?: throw IllegalArgumentException("Missing integer: $key")
    private fun JsonObject.number(key: String) = this[key]?.jsonPrimitive?.floatOrNull
        ?: throw IllegalArgumentException("Missing number: $key")
}
