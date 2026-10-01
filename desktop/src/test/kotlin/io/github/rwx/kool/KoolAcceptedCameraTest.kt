package io.github.rwx.kool

import io.github.rwx.PlatformStorage
import io.github.rwx.kool.vulkan.VulkanFrameLifecycle
import io.github.rwx.input.MultiTouchPointerState
import io.github.rwx.platform.CoreGameView
import io.github.rwx.render.canvas.*
import io.github.rwx.session.GameCameraSnapshot
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KoolAcceptedCameraTest {
    private fun session() = KoolDesktopGameSession(Proxy.newProxyInstance(
        PlatformStorage::class.java.classLoader, arrayOf(PlatformStorage::class.java),
    ) { _, method, _ -> error("Unexpected storage access: ${method.name}") } as PlatformStorage)

    @Test fun `mailbox consumption and rejected native present preserve acknowledged camera`() {
        val session = session()
        val owner = Any()
        val store = KoolCanvasCpuTextureStore()
        val mailbox = KoolDesktopGameSession::class.java.getDeclaredField("mailbox")
            .apply { isAccessible = true }.get(session) as LatestFrameMailbox
        val first = GameCameraSnapshot(1, 1, KoolCanvasViewport(1280, 720), 100f, 200f, 2f, 0)
        fun publish(camera: GameCameraSnapshot) = store.freezeFrame(
            KoolCanvasFrame(camera.viewport, emptyList()), camera.revision, camera.generation,
            1, camera.viewportRevision, camera,
        ).also { mailbox.publish(it); session.currentFrame() }
        try {
            val firstFrame = publish(first)
            assertSame(firstFrame, session.currentFrameEnvelope())
            assertNull(session.cameraSnapshot())
            CanvasFramePresentation.beginFrame()
            CanvasFramePresentation.chosen(owner, session.canvasPresentationTracker, 1, 0, first)
            VulkanFrameLifecycle.presented(false)
            assertNull(session.cameraSnapshot())

            CanvasFramePresentation.chosen(owner, session.canvasPresentationTracker, 1, 0, first)
            VulkanFrameLifecycle.presented(true)
            assertSame(first, session.cameraSnapshot())
            val unseen = first.copy(revision = 2, viewportRevision = 2,
                viewport = KoolCanvasViewport(1920, 1080), x = 900f)
            publish(unseen)
            assertSame(first, session.cameraSnapshot())
            CanvasFramePresentation.chosen(owner, session.canvasPresentationTracker, 2, 0, unseen)
            VulkanFrameLifecycle.presented(false)
            assertSame(first, session.cameraSnapshot())
            CanvasFramePresentation.chosen(owner, session.canvasPresentationTracker, 2, 0, unseen)
            VulkanFrameLifecycle.presented(true)
            assertSame(unseen, session.cameraSnapshot())
        } finally { CanvasFramePresentation.clear(owner); session.close() }
        assertNull(session.cameraSnapshot())
    }

    @Test fun `pointer down and release remain queued before the first accepted camera`() {
        val session = session()
        // The owner applied its viewport, but no native picture has been accepted yet.
        KoolDesktopGameSession::class.java.getDeclaredField("appliedViewport").apply { isAccessible = true }
            .set(session, KoolCanvasViewport(1280, 720))
        val view = KoolDesktopGameSession::class.java.getDeclaredField("view")
            .apply { isAccessible = true }.get(session) as CoreGameView
        val downField = MultiTouchPointerState::class.java.getDeclaredField("isDown").apply { isAccessible = true }
        try {
            assertNull(session.cameraSnapshot())
            session.submitPointer(320f, 200f, true, 0)
            session.submitSessionTask {
                assertTrue(downField.getBoolean(view.settings))
                assertEquals(320f, view.settings.x[0])
                assertEquals(200f, view.settings.y[0])
            }.get(5, TimeUnit.SECONDS)
            session.submitPointer(320f, 200f, false, 0)
            session.submitSessionTask { assertFalse(downField.getBoolean(view.settings)) }.get(5, TimeUnit.SECONDS)
            assertNull(session.cameraSnapshot())
        } finally { session.close() }
    }
}
