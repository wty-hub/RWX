package io.github.rwx.render.canvas

import io.github.rwx.session.GameCameraSnapshot
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class CanvasFramePresentationTest {
    private val owner = Any()
    private fun camera(sequence: Long, generation: Long = 1) =
        GameCameraSnapshot(sequence, sequence, KoolCanvasViewport(1280, 720),
            sequence * 100f, 200f, 2f, generation)

    @Test fun `selected but rejected picture never becomes an input camera`() {
        val tracker = CanvasFramePresentationTracker()
        val bridge = CanvasFramePresentationBridge()
        val first = camera(1)
        bridge.chosen(owner, tracker, first.revision, first.generation, first)
        assertNull(tracker.cameraSnapshot()) // Includes headless / missing native acknowledgement.
        assertFalse(bridge.presented(false))
        assertNull(tracker.cameraSnapshot())

        bridge.chosen(owner, tracker, first.revision, first.generation, first)
        assertTrue(bridge.presented(true))
        val unseen = camera(2).copy(viewport = KoolCanvasViewport(1920, 1080))
        bridge.chosen(owner, tracker, unseen.revision, unseen.generation, unseen)
        assertSame(first, tracker.cameraSnapshot())
        assertFalse(bridge.presented(false))
        assertSame(first, tracker.cameraSnapshot())
    }

    @Test fun `accepted and repeated presents retain the exact immutable camera`() {
        val tracker = CanvasFramePresentationTracker()
        val bridge = CanvasFramePresentationBridge()
        val first = camera(1)
        repeat(3) {
            bridge.beginFrame()
            bridge.chosen(owner, tracker, first.revision, first.generation, first)
            assertTrue(bridge.presented(true))
            assertSame(first, tracker.cameraSnapshot())
        }
        assertFalse(bridge.presented(true)) // A native callback alone cannot invent a picture.
        val next = camera(2)
        bridge.chosen(owner, tracker, next.revision, next.generation, next)
        assertTrue(bridge.presented(true))
        assertSame(next, tracker.cameraSnapshot())
    }

    @Test fun `failed acquire cannot leak its selection into a later nongame present`() {
        val tracker = CanvasFramePresentationTracker()
        val bridge = CanvasFramePresentationBridge()
        bridge.chosen(owner, tracker, 1, 1, camera(1))
        // No present callback follows failed acquire. The next frontend frame contains only UI.
        bridge.beginFrame()
        assertFalse(bridge.presented(true))
        assertNull(tracker.cameraSnapshot())
    }

    @Test fun `older sequence and previous session generation cannot replace accepted state`() {
        val tracker = CanvasFramePresentationTracker()
        val old = tracker.candidate(100, 1, camera(100))!!
        val new = tracker.candidate(1, 2, camera(1, 2))!!
        assertTrue(tracker.acknowledge(old))
        assertTrue(tracker.acknowledge(new))
        assertFalse(tracker.acknowledge(old))
        assertSame(new.camera, tracker.cameraSnapshot())

        val later = tracker.candidate(2, 2, camera(2, 2))!!
        assertTrue(tracker.acknowledge(later))
        assertFalse(tracker.acknowledge(new))
        assertSame(later.camera, tracker.cameraSnapshot())
    }

    @Test fun `accepted empty picture clears camera and blocks old session acknowledgement`() {
        val tracker = CanvasFramePresentationTracker()
        val old = tracker.candidate(5, 1, camera(5))!!
        assertTrue(tracker.acknowledge(old))
        assertTrue(tracker.acknowledge(tracker.candidate(6, 2, null)!!))
        assertNull(tracker.cameraSnapshot())
        assertFalse(tracker.acknowledge(old))
        assertNull(tracker.cameraSnapshot())
    }

    @Test fun `scene cleanup invalidates queued acknowledgement but allows a recreated scene`() {
        val tracker = CanvasFramePresentationTracker()
        val old = tracker.candidate(1, 1, camera(1))!!
        assertTrue(tracker.acknowledge(old))
        tracker.clear()
        assertNull(tracker.cameraSnapshot())
        assertFalse(tracker.acknowledge(old))
        val new = tracker.candidate(2, 1, camera(2))!!
        assertTrue(tracker.acknowledge(new))
        assertSame(new.camera, tracker.cameraSnapshot())
    }

    @Test fun `session close prevents late present from resurrecting an input camera`() {
        val tracker = CanvasFramePresentationTracker()
        val bridge = CanvasFramePresentationBridge()
        val first = camera(1)
        bridge.chosen(owner, tracker, 1, 1, first)
        assertTrue(bridge.presented(true))
        bridge.chosen(owner, tracker, 2, 1, camera(2))
        tracker.close()
        assertFalse(bridge.presented(true))
        assertNull(tracker.cameraSnapshot())
        bridge.chosen(owner, tracker, 3, 1, camera(3))
        assertFalse(bridge.presented(true))
        assertNull(tracker.cameraSnapshot())
    }

    @Test fun `cleanup clears only the releasing scene selection`() {
        val tracker = CanvasFramePresentationTracker()
        val bridge = CanvasFramePresentationBridge()
        val otherOwner = Any()
        val first = camera(1)
        bridge.chosen(otherOwner, tracker, 1, 1, first)
        bridge.clear(owner)
        assertTrue(bridge.presented(true))
        bridge.chosen(otherOwner, tracker, 2, 1, camera(2))
        bridge.clear(otherOwner)
        assertFalse(bridge.presented(true))
        assertSame(first, tracker.cameraSnapshot())
    }
}
