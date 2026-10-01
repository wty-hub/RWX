package io.github.rwx.benchmark

import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.UnitType
import com.corrodinggames.rts.game.units.UnitTypeEnum
import com.corrodinggames.rts.game.units.custom.CustomUnitConfig
import kotlinx.serialization.json.*
import java.io.File

/** Inventory is wider than the editor menu, including replacement aliases and hidden configs. */
object VanillaUnitCatalog {
    data class Entry(
        val sourceId: String,
        val effectiveId: String,
        val effectiveType: UnitType,
        val active: Boolean,
        val hidden: Boolean,
        val internal: Boolean,
        val building: Boolean,
        val movement: String,
        val upgradeLevels: List<Int>,
        val turretCount: Int,
        val legCount: Int,
        val attachmentSlots: List<String>,
        val transportCapacity: Int,
        val sourcePath: String?,
        val requiredScenarios: List<String>,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("source", sourceId); put("effective", effectiveId)
            put("active", active); put("hidden", hidden); put("internal", internal)
            put("building", building); put("movement", movement)
            put("upgradeLevels", JsonArray(upgradeLevels.map(::JsonPrimitive)))
            put("turretCount", turretCount); put("legCount", legCount)
            put("attachmentSlots", JsonArray(attachmentSlots.map(::JsonPrimitive)))
            put("transportCapacity", transportCapacity)
            sourcePath?.let { put("sourcePath", it) }
            put("requiredScenarios", JsonArray(requiredScenarios.map(::JsonPrimitive)))
            put("validation", "not-run")
        }
    }

    data class Definition(
        val relativePath: String,
        val name: String?,
        val template: Boolean,
        val hidden: Boolean,
        val copies: List<String>,
        val transformations: List<String>,
        val attachmentSpawns: List<String>,
    ) {
        fun json(): JsonObject = buildJsonObject {
            put("path", relativePath); name?.let { put("name", it) }
            put("template", template); put("hidden", hidden)
            put("copyFrom", JsonArray(copies.map(::JsonPrimitive)))
            put("transformations", JsonArray(transformations.map(::JsonPrimitive)))
            put("attachmentSpawns", JsonArray(attachmentSpawns.map(::JsonPrimitive)))
        }
    }

    fun entries(): List<Entry> {
        val configs = (CustomUnitConfig.allConfigs + CustomUnitConfig.activeConfigs)
            .filter { it.modInfo == null }.distinct()
        val sources: List<UnitType> = UnitTypeEnum.entries + configs
        return sources.map { source ->
            val effective = CustomUnitConfig.unitTypeOverrides[source] ?: source
            val custom = effective as? CustomUnitConfig
            val prototype = BaseUnit.bG[effective] as? BaseUnit
            val active = custom?.let { it in CustomUnitConfig.activeConfigs }
                ?: (prototype != null)
            val internal = (source is UnitTypeEnum && source.createUnit()) ||
                (custom != null && custom.isUnselectable && custom.ignoreInUnitCapCalculation)
            val upgrades = if (source is UnitTypeEnum) {
                listOf(1) + (2..10).takeWhile { source.getUpgradeCost(it) > 0 }
            } else listOf(1)
            val slots = custom?.attachmentSlotDefinitions?.map { it.b() }.orEmpty()
            val transport = custom?.maxTransportingUnits ?: prototype?.maxTransportWeight ?: 0
            val building = effective.isBuildingUnit()
            val scenarios = buildList {
                add("idle"); add("selection"); add("damage"); add("death")
                if (!building && !internal) add("movement")
                if (prototype?.canUnitAttack() == true || prototype?.canAttack() == true) add("attack")
                if (building) { add("construction"); add("repair") }
                if (upgrades.size > 1) add("upgrade")
                if (transport > 0) { add("load"); add("unload") }
                if (slots.isNotEmpty()) add("parent-created-attachments")
                if ((custom?.legConfig?.size ?: 0) > 0) add("leg-movement-and-rotation")
                if ((prototype?.unitEnergyMax ?: 0f) > 0f) add("shield")
                if (custom != null && !custom.showInEditor) add("hidden-form-or-transition")
            }
            Entry(id(source), id(effective), effective, active,
                custom?.showInEditor == false || internal, internal, building,
                prototype?.movementType?.name ?: custom?.movementType?.name ?: "UNAVAILABLE",
                upgrades, custom?.turrets?.size ?: prototype?.movementLevels?.size ?: 0,
                custom?.legConfig?.size ?: 0, slots, transport, custom?.configPath, scenarios)
        }.sortedBy { it.sourceId }
    }

    fun definitions(root: File): List<Definition> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.extension.equals("ini", true) }
            .map { file ->
                var section = ""
                val values = mutableListOf<Triple<String, String, String>>()
                file.forEachLine { source ->
                    val line = source.substringBefore('#').trim()
                    if (line.startsWith('[') && line.endsWith(']')) section = line.substring(1, line.length - 1)
                    else {
                        val separator = line.indexOf(':').takeIf { it >= 0 } ?: line.indexOf('=').takeIf { it >= 0 }
                        if (separator != null) values += Triple(section, line.substring(0, separator).trim(), line.substring(separator + 1).trim())
                    }
                }
                fun core(key: String) = values.firstOrNull { it.first == "core" && it.second.equals(key, true) }?.third
                fun matching(key: String) = values.filter { it.second.equals(key, true) }.map { it.third }.distinct()
                Definition(file.relativeTo(root).invariantSeparatorsPath, core("name"),
                    core("dont_load")?.equals("true", true) == true,
                    core("showInEditor")?.equals("false", true) == true,
                    core("copyFrom")?.split(',')?.map(String::trim).orEmpty(), matching("convertTo"), matching("onCreateSpawnUnitOf"))
            }.sortedBy { it.relativePath }.toList()
    }

    fun report(root: File = File("assets/units")): JsonObject {
        val entries = entries()
        val definitions = definitions(root)
        return buildJsonObject {
            put("schema", 1)
            put("registry", JsonArray(entries.map(Entry::json)))
            put("definitions", JsonArray(definitions.map(Definition::json)))
            put("builtinCount", UnitTypeEnum.entries.size)
            put("activeVanillaConfigCount", CustomUnitConfig.activeConfigs.count { it.modInfo == null })
            put("inactiveVanillaConfigCount", entries.count { !it.active && it.sourceId.startsWith("config:") })
            put("coverageStatus", "inventory-only; scenario results required")
        }
    }

    fun id(type: UnitType): String = when (type) {
        is UnitTypeEnum -> "builtin:${type.name}"
        is CustomUnitConfig -> "config:${type.name}"
        else -> "unknown:${type.getUnitTypeDescriptionShort()}"
    }
}
