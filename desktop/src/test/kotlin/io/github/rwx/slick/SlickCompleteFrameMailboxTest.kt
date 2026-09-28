package io.github.rwx.slick

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SlickCompleteFrameMailboxTest {
    @Test
    fun `latest frame replaces an older frame without a queue`() {
        val mailbox = SlickCompleteFrameMailbox()
        mailbox.requestNext()
        val first = mailbox.beginCapture(2, 2)!!
        first.pixels[0] = 11
        mailbox.publish(first)

        mailbox.requestNext()
        val second = mailbox.beginCapture(2, 2)!!
        second.pixels[0] = 22
        mailbox.publish(second)

        var seen = 0
        assertTrue(mailbox.consumeLatest { seen = it.pixels[0] })
        assertEquals(22, seen)
        assertFalse(mailbox.consumeLatest { error("unexpected old frame") })
    }

    @Test
    fun `producer never overwrites a frame while the consumer uploads it`() {
        val mailbox = SlickCompleteFrameMailbox()
        mailbox.requestNext()
        val first = mailbox.beginCapture(2, 2)!!
        mailbox.publish(first)

        mailbox.consumeLatest { reading ->
            mailbox.requestNext()
            val writing = mailbox.beginCapture(2, 2)!!
            assertNotSame(reading, writing)
            mailbox.publish(writing)
        }
        assertTrue(mailbox.consumeLatest { assertNotSame(first, it) })
    }

    @Test
    fun `a frame is delivered only after every pixel has been written`() {
        val mailbox = SlickCompleteFrameMailbox()
        mailbox.requestNext()
        val writing = mailbox.beginCapture(3, 2)!!
        writing.pixels[0] = 0xff102030.toInt()
        assertFalse(mailbox.consumeLatest { error("partial frame was visible") })

        writing.pixels.fill(0xff102030.toInt())
        mailbox.publish(writing)
        assertTrue(mailbox.consumeLatest { delivered ->
            assertEquals(6, delivered.pixels.size)
            assertTrue(delivered.pixels.all { it == 0xff102030.toInt() })
        })
    }

    @Test
    fun `clearing during capture prevents a stale frame from being delivered`() {
        val mailbox = SlickCompleteFrameMailbox()
        mailbox.requestNext()
        val writing = mailbox.beginCapture(2, 2)!!
        mailbox.clear()
        mailbox.publish(writing)
        assertFalse(mailbox.consumeLatest { error("frame from an old scene was visible") })
    }

    @Test
    fun `resize changes capacity and clear discards a pending frame`() {
        val mailbox = SlickCompleteFrameMailbox()
        mailbox.requestNext()
        val first = mailbox.beginCapture(2, 2)!!
        val oldPixels = first.pixels
        mailbox.publish(first)
        mailbox.clear()
        assertFalse(mailbox.consumeLatest { error("stale frame") })

        mailbox.requestNext()
        val resized = mailbox.beginCapture(4, 3)!!
        assertEquals(12, resized.pixels.size)
        mailbox.publish(resized)
        assertTrue(mailbox.consumeLatest { assertEquals(4, it.width) })

        mailbox.requestNext()
        val sameSize = mailbox.beginCapture(4, 3)!!
        mailbox.publish(sameSize)
        assertTrue(mailbox.consumeLatest { assertSame(resized.pixels, it.pixels) })
        assertEquals(4, oldPixels.size)
    }

    @Test
    fun `clear invalidates a capture that was already in progress`() {
        val mailbox = SlickCompleteFrameMailbox()
        mailbox.requestNext()
        val stale = mailbox.beginCapture(2, 2)!!
        stale.pixels[0] = 11
        mailbox.clear()
        mailbox.publish(stale)
        assertFalse(mailbox.consumeLatest { error("capture from the previous generation") })

        mailbox.requestNext()
        val fresh = mailbox.beginCapture(3, 1)!!
        fresh.pixels[0] = 22
        mailbox.publish(fresh)
        assertTrue(mailbox.consumeLatest {
            assertEquals(3, it.width)
            assertEquals(1, it.height)
            assertEquals(22, it.pixels[0])
        })
        assertFalse(mailbox.consumeLatest { error("new frame consumed twice") })
    }
}
