package io.github.rwx.slick

import de.fabmax.kool.util.Uint8Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals

class SlickOpaqueFramePackerTest {
    @Test
    fun `packs opaque ARGB pixels as RGBA bytes`() {
        val destination = Uint8Buffer(8)
        desktopOpaqueFramePacker.pack(
            destination,
            intArrayOf(0xff123456.toInt(), 0xffabcdef.toInt()),
            2,
        )

        assertContentEquals(
            listOf(0x12, 0x34, 0x56, 0xff, 0xab, 0xcd, 0xef, 0xff),
            List(8) { destination[it].toInt() },
        )
    }
}
