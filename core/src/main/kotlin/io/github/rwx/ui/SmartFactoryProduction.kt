package io.github.rwx.ui

import com.corrodinggames.rts.game.units.BaseUnit
import com.corrodinggames.rts.game.units.OrderableUnit
import com.corrodinggames.rts.game.units.actions.AbstractUnitAction
import com.corrodinggames.rts.game.units.buildings.FactoryQueueInterface
import com.corrodinggames.rts.gameFramework.GameEngine

/**
 * One factory the player can queue a unit on. [idle] is an empty build queue.
 * [totalQueue] is everything that factory is already building. [actionQueue] is how many of the
 * clicked unit are already queued, which is what a right-click removes.
 */
data class FactoryProductionCandidate(
    val id: Long,
    val idle: Boolean,
    val totalQueue: Int,
    val actionQueue: Int,
)

/**
 * Picks which selected factory receives each unit in one production click.
 *
 * Single-unit mode spreads a batch (shift / ctrl) across the least busy factories.
 * All-factories mode visits each factory once per round, shortest queues first, so those factories
 * spend the available resources first when the player cannot afford the entire round.
 * The commands themselves are still the original single-factory build commands.
 */
fun chooseFactorySlots(
    candidates: List<FactoryProductionCandidate>,
    count: Int,
    cancel: Boolean,
    singleUnit: Boolean = true,
): List<Int> {
    if (candidates.isEmpty() || count <= 0) return emptyList()
    if (!singleUnit) {
        val ordered = candidates.indices.sortedWith(
            compareBy<Int> { candidates[it].totalQueue }.thenBy { candidates[it].id },
        )
        return List(count) { ordered }.flatten()
    }
    val idle = candidates.map { it.idle }.toBooleanArray()
    val total = candidates.map { it.totalQueue }.toIntArray()
    val action = candidates.map { it.actionQueue }.toIntArray()
    return List(count) {
        val index = if (cancel) {
            candidates.indices.maxWith(
                compareBy<Int> { action[it] }
                    .thenBy { -candidates[it].id },
            )
        } else {
            candidates.indices.minWith(
                compareBy<Int> { if (idle[it]) 0 else 1 }
                    .thenBy { total[it] }
                    .thenBy { candidates[it].id },
            )
        }
        if (cancel) {
            if (action[index] > 0) action[index] -= 1
            if (total[index] > 0) total[index] -= 1
            idle[index] = total[index] == 0
        } else {
            total[index] += 1
            action[index] += 1
            idle[index] = false
        }
        index
    }
}

object SmartFactoryProduction {
    /**
     * Factories that should receive this click, one entry per ordered unit.
     * All-factories mode is the default and orders factories by queue length before spending.
     * Returns null when fewer than two eligible factories are selected or the action is not
     * factory production, so the caller keeps the original behavior.
     */
    @JvmStatic
    fun productionTargets(
        selected: Iterable<BaseUnit>,
        action: AbstractUnitAction,
        count: Int,
        cancel: Boolean,
    ): List<OrderableUnit>? {
        val engine = GameEngine.getInstance() ?: return null
        if (!action.isHighPriority || action.isOnlyOneUnitAtATime || count <= 0) return null
        val actionId = action.actionId ?: return null
        val gameUi = engine.gameUI ?: return null
        val eligible = ArrayList<Pair<OrderableUnit, FactoryProductionCandidate>>()
        for (unit in selected) {
            if (unit !is OrderableUnit || !unit.isSelected || !gameUi.canControlUnit(unit)) continue
            if (unit !is FactoryQueueInterface) continue
            val resolved = unit.validateActionId(actionId) ?: continue
            if (!resolved.b(unit)) continue
            val factory = unit as FactoryQueueInterface
            eligible += unit to FactoryProductionCandidate(
                id = unit.objectId,
                idle = factory.dy(),
                totalQueue = factory.f(false),
                actionQueue = factory.a(actionId, true),
            )
        }
        if (eligible.size < 2) return null
        return chooseFactorySlots(
            eligible.map { it.second }, count, cancel, engine.settingsEngine.singleUnitProduction,
        ).map { eligible[it].first }
    }
}
