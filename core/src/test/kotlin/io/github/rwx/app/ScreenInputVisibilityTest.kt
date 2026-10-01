package io.github.rwx.app

import de.fabmax.kool.KoolConfigJvm
import de.fabmax.kool.KoolSystem
import de.fabmax.kool.input.InputStack
import de.fabmax.kool.modules.ui2.UiSurface
import de.fabmax.kool.scene.Node
import de.fabmax.kool.scene.Scene
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenInputVisibilityTest {
    init { if (!KoolSystem.isInitialized) KoolSystem.initialize(KoolConfigJvm()) }

    @Test fun `hiding lobby removes registered nested surfaces without removing active overlays`() {
        val lobby = Scene("lobby")
        val container = Node("panel-container")
        val list = UiSurface(lobby, name = "room-list")
        val overlayScene = Scene("overlay")
        val overlay = UiSurface(overlayScene, name = "dialog")
        lobby.addNode(container)
        container.addNode(list)
        val mode = list.inputMode
        try {
            InputStack.pushTop(list.inputHandler)
            InputStack.pushTop(overlay.inputHandler)
            InputStack.updateHandlerStack()

            lobby.setScreenVisible(false)
            InputStack.updateHandlerStack()
            assertFalse(lobby.isVisible)
            assertFalse(list.inputHandler in InputStack.handlerStack)
            assertTrue(overlay.inputHandler in InputStack.handlerStack)
            assertEquals(mode, list.inputMode)

            lobby.setScreenVisible(true)
            InputStack.pushTop(list.inputHandler)
            InputStack.updateHandlerStack()
            assertTrue(lobby.isVisible)
            assertTrue(list.inputHandler in InputStack.handlerStack)
        } finally {
            InputStack.handlerStack.stageRemove(list.inputHandler)
            InputStack.handlerStack.stageRemove(overlay.inputHandler)
            InputStack.updateHandlerStack()
        }
    }

    @Test fun `hiding scene cancels surface registration pending in the same frame`() {
        val lobby = Scene("lobby")
        val list = UiSurface(lobby, name = "room-list")
        lobby.addNode(list)
        try {
            InputStack.pushTop(list.inputHandler)
            assertFalse(list.inputHandler in InputStack.handlerStack)
            lobby.setScreenVisible(false)
            InputStack.updateHandlerStack()
            assertFalse(list.inputHandler in InputStack.handlerStack)
        } finally {
            InputStack.handlerStack.stageRemove(list.inputHandler)
            InputStack.updateHandlerStack()
        }
    }
}
