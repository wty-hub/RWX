package io.github.rwx.ui.component

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ListEndFollowTest {
    @Test
    fun `opens on the latest message and follows new ones`() {
        val initial = nextListEndFollow(ListEndFollow(), itemCount = 4, laidOut = false, atEnd = false)
        assertEquals(3, initial.scrollToIndex)

        val arrived = nextListEndFollow(initial.follow, itemCount = 4, laidOut = true, atEnd = true)
        assertEquals(3, arrived.scrollToIndex)

        val opened = nextListEndFollow(arrived.follow, itemCount = 4, laidOut = true, atEnd = true)
        assertNull(opened.scrollToIndex)
        assertEquals(true, opened.follow.pinnedToEnd)

        val appended = nextListEndFollow(opened.follow, itemCount = 5, laidOut = true, atEnd = false)
        assertEquals(4, appended.scrollToIndex)
    }

    @Test
    fun `keeps the reading position when the user has scrolled up`() {
        val opened = ListEndFollow(itemCount = 4, pinnedToEnd = true, reachedEnd = true)
        val scrolledUp = nextListEndFollow(opened, itemCount = 4, laidOut = true, atEnd = false)
        assertNull(scrolledUp.scrollToIndex)
        assertEquals(false, scrolledUp.follow.pinnedToEnd)

        val appended = nextListEndFollow(scrolledUp.follow, itemCount = 5, laidOut = true, atEnd = false)
        assertNull(appended.scrollToIndex)
    }
}
