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
 * Idle factories come first. A batch (shift / ctrl) is spread across them before anyone gets a
 * second unit. The commands themselves are still the original single-factory build commands.
 */
fun chooseFactorySlots(
    candidates: List<FactoryProductionCandidate>,
    count: Int,
    cancel: Boolean,
): List<Int> {
    if (candidates.isEmpty() || count <= 0) return emptyList()
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
     * Returns null when fewer than two eligible factories are selected, so the caller keeps the
     * original "every selected factory builds one" behavior.
     */
    @JvmStatic
    fun productionTargets(
        selected: Iterable<BaseUnit>,
        action: AbstractUnitAction,
        count: Int,
        cancel: Boolean,
    ): List<OrderableUnit>? {
        if (!action.isHighPriority || action.isOnlyOneUnitAtATime || count <= 0) return null
        val actionId = action.actionId ?: return null
        val gameUi = GameEngine.getInstance()?.gameUI ?: return null
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
        return chooseFactorySlots(eligible.map { it.second }, count, cancel).map { eligible[it].first }
    }
}
