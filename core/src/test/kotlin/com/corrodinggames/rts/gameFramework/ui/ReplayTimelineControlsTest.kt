package com.corrodinggames.rts.gameFramework.ui

import kotlin.test.*

class ReplayTimelineControlsTest {
    @Test fun `drag captures outside track previews and seeks only on release`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(800f, 1f, 80f)
        val requests = mutableListOf<Int>()
        assertTrue(controls.handle(layout, true, layout.trackLeft(), 90f, 5000, 100_000, requests::add))
        assertEquals(0, controls.previewMillis)
        assertTrue(controls.handle(layout, true, 2000f, 500f, 5000, 100_000, requests::add))
        assertEquals(100_000, controls.previewMillis)
        assertTrue(requests.isEmpty())
        assertTrue(controls.handle(layout, false, 2000f, 500f, 5000, 100_000, requests::add))
        assertEquals(listOf(100_000), requests)
        assertFalse(controls.isCaptured)
    }

    @Test fun `buttons clamp and missing duration captures without seeking`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(320f, 2f, 80f)
        val requests = mutableListOf<Int>()
        for ((x, current, expected) in listOf(Triple(layout.left() + 1, 3000, 0),
                Triple(layout.left() + layout.width() - 1, 98_000, 100_000))) {
            controls.handle(layout, true, x, 90f, current, 100_000, requests::add)
            controls.handle(layout, false, x, 90f, current, 100_000, requests::add)
            assertEquals(expected, requests.last())
        }
        controls.handle(layout, true, layout.trackLeft(), 90f, 0, -1, requests::add)
        controls.handle(layout, false, layout.trackLeft(), 90f, 0, -1, requests::add)
        assertEquals(2, requests.size)
        assertTrue(layout.left() >= 0)
        assertTrue(layout.trackRight() > layout.trackLeft())
    }

    @Test fun `duration unavailable does not create a seek request`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(800f, 1f, 80f)
        val requests = mutableListOf<Int>()
        controls.handle(layout, true, layout.trackLeft(), 90f, 5000, -1, requests::add)
        controls.handle(layout, false, layout.trackLeft(), 90f, 5000, -1, requests::add)
        assertTrue(requests.isEmpty())
    }

    @Test fun `press beginning on map is never converted into timeline drag`() {
        val controls = ReplayTimelineControls()
        val layout = ReplayTimelineControls.layout(1280f, 1f, 80f)
        assertFalse(controls.handle(layout, true, 0f, 500f, 0, 1000) { fail("unexpected seek") })
        assertFalse(controls.handle(layout, true, layout.trackLeft(), 90f, 0, 1000) { fail("unexpected seek") })
        assertFalse(controls.handle(layout, false, layout.trackLeft(), 90f, 0, 1000) { fail("unexpected seek") })
    }
}
