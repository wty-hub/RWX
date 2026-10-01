package io.github.rwx.benchmark

import com.corrodinggames.rts.game.PlayerTeam
import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.UnitMovementType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.GameObject
import com.corrodinggames.rts.gameFramework.network.GameOutputStream
import io.github.rwx.render.canvas.*
import kotlinx.serialization.json.*
import java.io.File

/** Actual original constructors, updates, serializers and draw extraction; no GPU is required.
 * This smoke test deliberately reports transport/action/visual scenario coverage as outstanding.
 */
object VanillaUnitSmokeHarness {
    private data class Fixture(val entry: VanillaUnitCatalog.Entry, val level: Int, val unit: BaseUnit,
        var idleCommands: Int = 0, var damagedCommands: Int = 0, var deathCommands: Int = 0)

    fun run(engine: GameEngine, output: File, ticks: Int = 120): Boolean {
        check(!engine.networkEngine.networkGameActive && !engine.replayEngine.i())
        val entries = VanillaUnitCatalog.entries()
        for (unit in GameObject.fastGameObjectList.filterIsInstance<BaseUnit>().toList()) unit.removeFromGame()
        GameObject.dL()
        val teams = VanillaBattleBenchmark.initializeScriptedTeams(engine)
        engine.playerTeam = teams.first()
        val failures = mutableListOf<JsonObject>()
        val helpers = entries.filter { it.sourceId.startsWith("builtin:") && it.internal }
        val fixtures = mutableListOf<Fixture>()
        val positions = mutableMapOf<UnitMovementType, List<Pair<Float, Float>>>()
        val counters = mutableMapOf<UnitMovementType, Int>()
        for (entry in entries.filter { it.active && it !in helpers }) {
            for (level in entry.upgradeLevels) try {
                val source = if (entry.sourceId.startsWith("builtin:"))
                    UnitTypeEnum.valueOf(entry.sourceId.removePrefix("builtin:")) else entry.effectiveType
                val unit = source.a()
                if (level > 1) (unit as? OrderableUnit)?.a(level)
                val movement = unit.movementType.takeUnless { it == UnitMovementType.NONE || it == UnitMovementType.BUILDING }
                    ?: UnitMovementType.LAND
                val points = positions.getOrPut(movement) {
                    buildList {
                        for (y in 2 until engine.tileMap.tileCountY - 2 step 2)
                            for (x in 2 until engine.tileMap.tileCountX - 2 step 2)
                                if (!engine.pathfindingEngine.isTileBlockedForMovement(movement, x, y))
                                    add((x + .5f) * engine.tileMap.tileWorldSizeX to (y + .5f) * engine.tileMap.tileWorldSizeY)
                    }
                }
                check(points.isNotEmpty()) { "Map has no terrain for $movement" }
                val index = counters[movement] ?: 0
                counters[movement] = index + 1
                val point = points[index % points.size]
                unit.posX = point.first; unit.posY = point.second
                unit.setUnitTeam(teams.first()); unit.isActive = true; unit.n()
                PlayerTeam.c(unit); engine.unitSpatialIndex.a(unit)
                GameOutputStream().also(unit::a).toByteArray()
                fixtures += Fixture(entry, level, unit)
            } catch (error: Throwable) {
                failures += buildJsonObject { put("source", entry.sourceId); put("level", level)
                    put("phase", "constructor-or-serializer"); put("error", error.toString()) }
            }
        }
        GameObject.dL()
        // Textures decoded during the headless bootstrap retain real CPU pixels. The recording
        // backend registers those pixels on first use, exercising the same frame boundary.
        val store = KoolCanvasCpuTextureStore()
        val graphics = KoolGraphicsEngine(store)
        engine.renderGraphicsEngine = graphics
        var sequence = 0L
        fun draw(fixture: Fixture): Int {
            val unit = fixture.unit
            engine.setViewpoint(unit.posX - engine.halfVisibleWorldWidth, unit.posY - engine.halfVisibleWorldHeight)
            unit.shouldDraw = true
            graphics.beginFrame(engine.screenWidth.toInt(), engine.screenHeight.toInt())
            graphics.i()
            try {
                // Original normal-detail order: underlay, selection, body, overlay, waypoint.
                unit.d(.25f); unit.e(.25f); unit.c(.25f); unit.a(.25f, false); unit.p(.25f)
            } finally { graphics.j() }
            val frame = graphics.snapshot()
            store.freezeFrame(frame, ++sequence, 1, engine.currentTick, 1).use { envelope ->
                check(envelope.frame.commands.size == frame.commands.size)
                frame.commands.filterIsInstance<KoolCanvasCommand.DrawTexture>().forEach {
                    check(it.texture.width > 0 && it.texture.height > 0) { "Invalid texture extent" }
                }
            }
            return frame.commands.size
        }
        fun drawAll(phase: String, assign: (Fixture, Int) -> Unit) {
            for (fixture in fixtures) try { assign(fixture, draw(fixture)) }
            catch (error: Throwable) { failures += buildJsonObject {
                put("source", fixture.entry.sourceId); put("level", fixture.level)
                put("phase", phase); put("error", error.toString())
            } }
        }
        fixtures.forEach { engine.gameUI.selectUnit(it.unit) }
        drawAll("idle-and-selected") { fixture, count -> fixture.idleCommands = count }
        for (fixture in fixtures) {
            val unit = fixture.unit
            if (unit is OrderableUnit && unit.moveSpeed > 0f) engine.commandController.createCommandForTeam(teams.first()).apply {
                addUnitToCommand(unit); setMoveTarget(unit.posX + 20f, unit.posY)
            }
        }
        repeat(ticks) { engine.gameLoop(1f, 16) }
        val parentCreated = GameObject.fastGameObjectList.filterIsInstance<BaseUnit>()
            .filter { it.parentEntity != null }.groupingBy { VanillaUnitCatalog.id(it.r()) }.eachCount()
        for (fixture in fixtures) fixture.unit.applyDamage(null, maxOf(1f, fixture.unit.maxHealth * .1f), null)
        drawAll("damaged-and-moved") { fixture, count -> fixture.damagedCommands = count }
        for (fixture in fixtures) fixture.unit.applyDamage(null,
            fixture.unit.maxHealth * 10f + fixture.unit.unitEnergyMax * 10f + 1f, null)
        repeat(2) { engine.gameLoop(1f, 16) }
        drawAll("death") { fixture, count -> fixture.deathCommands = count }
        output.parentFile?.mkdirs()
        output.writeText(buildJsonObject {
            put("kind", "vanilla-constructor-draw-smoke"); put("matched", failures.isEmpty())
            put("registryEntries", entries.size); put("constructedVariants", fixtures.size)
            put("ticks", ticks); put("framePacketsFrozen", sequence)
            put("variants", JsonArray(fixtures.map { fixture -> buildJsonObject {
                put("source", fixture.entry.sourceId); put("effective", fixture.entry.effectiveId)
                put("level", fixture.level); put("actualUpgradeLevel", fixture.unit.upgradeLevel)
                put("idleSelectedCommands", fixture.idleCommands); put("damagedMovedCommands", fixture.damagedCommands)
                put("deathCommands", fixture.deathCommands); put("deadAfterDamage", fixture.unit.isDead)
                put("detachedComponent", fixture.entry.internal)
            } }))
            put("engineHelpersExcluded", JsonArray(helpers.map { JsonPrimitive(it.sourceId) }))
            put("parentCreatedComponents", JsonObject(parentCreated.mapValues { JsonPrimitive(it.value) }))
            put("failures", JsonArray(failures))
            put("remaining", "GPU visual comparison, transport load/unload, every action/upgrade transition and each special-effect state; detached components are not parent behavior validation")
        }.toString() + "\n")
        return failures.isEmpty()
    }
}
