package io.github.rwx.app

import kotlin.test.Test
import kotlin.test.assertEquals

class PointerScaleTest {
    @Test
    fun `Kool framebuffer coordinates remain unchanged for framebuffer renderers`() {
        assertEquals(1f, pointerToGameScale(2f, usesLogicalPointerCoordinates = false))
    }

    @Test
    fun `Slick receives logical coordinates on a Retina display`() {
        assertEquals(0.5f, pointerToGameScale(2f, usesLogicalPointerCoordinates = true))
        assertEquals(1f, pointerToGameScale(1f, usesLogicalPointerCoordinates = true))
    }
}
