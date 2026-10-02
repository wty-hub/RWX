package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.network.GameRoomSettings
import io.github.rwx.render.RendererMode
import io.github.rwx.render.canvas.KoolCanvasFrame
import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.BattleRoomCoreConfig
import io.github.rwx.session.BattleRoomSnapshot
import io.github.rwx.session.GameSession
import io.github.rwx.ui.host.LoadingDialogSceneHost
import io.github.rwx.ui.model.BattleRoomPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BattleRoomJoinHistoryTest {
    @Test
    fun `a stale local room is not a successful join and only the connected room replaces history`() {
        val session = TestSession().apply { snapshot = room(network = false, host = true) }
        val previous = RememberedMultiplayerRoom("old:5123")
        val target = RememberedMultiplayerRoom("get|new-room|123|false|5123", "new-room")
        var remembered = previous
        val started = CountDownLatch(1)
        val controller = controller(session, { remembered = it })
        try {
            controller.start(target.connectDescriptor, "new room", "join failed", target) { started.countDown() }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            controller.drive()
            assertTrue(controller.isPending)
            assertEquals(previous, remembered)

            session.snapshot = room(network = true, host = false)
            driveUntilComplete(controller)
            assertEquals(target, remembered)
        } finally {
            controller.handleBattleRoomClosed()
        }
    }

    @Test
    fun `a failed join retains the last successfully entered room`() {
        val session = TestSession()
        val previous = RememberedMultiplayerRoom(p2pRoomId = "last-successful-room")
        var remembered = previous
        var failed = false
        val controller = controller(session, { remembered = it }, { failed = true })
        controller.start("bad:5123", "unavailable room", "join failed", RememberedMultiplayerRoom("bad:5123")) {
            error("Connection refused")
        }
        driveUntilComplete(controller)
        assertTrue(failed)
        assertEquals(previous, remembered)
    }

    private fun controller(
        session: TestSession,
        remember: (RememberedMultiplayerRoom) -> Unit,
        failed: (String) -> Unit = { error(it) },
    ) = BattleRoomJoinController(session, LoadingDialogSceneHost(), {}, {}, remember, failed)

    private fun driveUntilComplete(controller: BattleRoomJoinController) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (controller.isPending && System.nanoTime() < deadline) {
            controller.drive()
            Thread.sleep(10)
        }
        assertFalse(controller.isPending, "Join controller never completed")
    }

    private fun room(network: Boolean, host: Boolean) = BattleRoomSnapshot(
        BattleRoomCoreConfig("map.tmx", GameRoomSettings()), "Map", "Custom Map",
        listOf(BattleRoomPlayer("1", "Player", "1", "A")), isHost = host, isNetworkMultiplayer = network,
    )

    private class TestSession : GameSession() {
        @Volatile var snapshot: BattleRoomSnapshot? = null
        override val rendererMode: RendererMode = object : RendererMode { override val id = "test" }
        override fun currentBattleRoom(refreshNetworkStatus: Boolean) = snapshot
        override fun loadPendingMapNow() = KoolCanvasFrame(KoolCanvasViewport(1, 1), emptyList())
        override fun ensureStarted(viewport: KoolCanvasViewport): GameEngine = error("No engine needed")
        override fun applyViewport(engine: GameEngine, viewport: KoolCanvasViewport) = Unit
    }
}
