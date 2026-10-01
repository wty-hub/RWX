package io.github.rwx.mod.registry

import com.corrodinggames.rts.game.GameLogic
import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ui.GameUI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModUiEngineOwnershipTest {
    @Test fun `opening a mod window defers its engine flag mutation until owner dispatch`() = withEngine { engine ->
        val queued = ArrayDeque<() -> Unit>()
        UiRegistry.engineExecutor = { queued.addLast(it) }
        engine.gameUI.isDraggingSelection = true
        UiRegistry.cancelEngineSelectionDrag()
        assertTrue(engine.gameUI.isDraggingSelection)
        assertEquals(1, queued.size)
        queued.removeFirst().invoke()
        assertFalse(engine.gameUI.isDraggingSelection)
    }

    @Test fun `deferred mod UI action resolves the active engine on its owner`() = withEngine { previous ->
        val queued = ArrayDeque<() -> Unit>()
        UiRegistry.engineExecutor = { queued.addLast(it) }
        previous.gameUI.isDraggingSelection = true
        UiRegistry.cancelEngineSelectionDrag()
        instanceField.set(null, null)
        val active = GameLogic().also { it.gameUI = GameUI(); it.gameUI.isDraggingSelection = true }
        instanceField.set(null, active)
        queued.removeFirst().invoke()
        assertTrue(previous.gameUI.isDraggingSelection, "The queue must not retain a live old engine")
        assertFalse(active.gameUI.isDraggingSelection)
    }

    private fun withEngine(action: (GameLogic) -> Unit) {
        val previous = instanceField.get(null)
        val executor = UiRegistry.engineExecutor
        instanceField.set(null, null)
        try {
            val engine = GameLogic().also { it.gameUI = GameUI() }
            instanceField.set(null, engine)
            action(engine)
        } finally {
            instanceField.set(null, previous)
            UiRegistry.engineExecutor = executor
        }
    }

    private val instanceField = GameEngine::class.java.getDeclaredField("instance").apply { isAccessible = true }
}
