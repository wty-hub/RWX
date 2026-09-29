package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.util.Uint8Buffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class KoolCanvasResourcesTest {
    @Test
    fun `opaque frame copies source and forces alpha without transparent pixel bleed`() {
        val id = KoolCanvasTextureId("opaque-frame-source-ownership")
        val source = intArrayOf(0x00112233, 0x7f445566)
        try {
            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 2, 1, source)
            source[0] = 0xffaabbcc.toInt()

            val image = KoolCanvasTextureRegistry.argbImage(id)!!
            assertContentEquals(intArrayOf(0xff112233.toInt(), 0xff445566.toInt()), image.pixels)
            assertEquals(false, image.premultipliedAlpha)

            val texture = KoolCanvasTextureRegistry.resolve(KoolCanvasTextureRef(id, 2, 1), KoolCanvasTextureFilter.Nearest)
            val upload = texture.uploadData as BufferedImageData2d
            assertEquals(2, upload.width)
            assertEquals(1, upload.height)
            assertContentEquals(
                listOf(0x11, 0x22, 0x33, 0xff, 0x44, 0x55, 0x66, 0xff),
                (upload.data as Uint8Buffer).let { buffer -> List(buffer.capacity) { buffer[it].toInt() } },
            )
        } finally {
            KoolCanvasTextureRegistry.unregister(id)
        }
    }

    @Test
    fun `opaque updates keep the texture owner and only queue the newest upload`() {
        val id = KoolCanvasTextureId("opaque-frame-latest-upload")
        try {
            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 1, 1, intArrayOf(0xff010203.toInt()))
            val texture = KoolCanvasTextureRegistry.resolve(KoolCanvasTextureRef(id, 1, 1), KoolCanvasTextureFilter.Nearest)
            val firstUpload = texture.uploadData as BufferedImageData2d

            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 2, 1, intArrayOf(0xff040506.toInt(), 0xff070809.toInt()))
            val latestUpload = texture.uploadData as BufferedImageData2d
            assertSame(texture, KoolCanvasTextureRegistry.resolve(KoolCanvasTextureRef(id, 2, 1), KoolCanvasTextureFilter.Nearest))
            assertNotSame(firstUpload, latestUpload)
            assertEquals(2, latestUpload.width)
            assertEquals(1, latestUpload.height)
            assertContentEquals(
                listOf(0x04, 0x05, 0x06, 0xff, 0x07, 0x08, 0x09, 0xff),
                (latestUpload.data as Uint8Buffer).let { buffer -> List(buffer.capacity) { buffer[it].toInt() } },
            )

            texture.uploadData = null // Simulate the backend consuming the pending upload.
            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 1, 1, intArrayOf(0xff0a0b0c.toInt()))
            assertNotEquals(latestUpload.id, (texture.uploadData as BufferedImageData2d).id)
        } finally {
            KoolCanvasTextureRegistry.unregister(id)
        }
    }

    @Test
    fun `incomplete opaque frame leaves the previous image intact`() {
        val id = KoolCanvasTextureId("opaque-frame-completeness")
        try {
            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 1, 1, intArrayOf(0xff123456.toInt()))
            assertFailsWith<IllegalArgumentException> {
                KoolCanvasTextureRegistry.registerOpaqueArgb(id, 2, 1, intArrayOf(0xff654321.toInt()))
            }
            assertContentEquals(intArrayOf(0xff123456.toInt()), KoolCanvasTextureRegistry.argbImage(id)!!.pixels)
        } finally {
            KoolCanvasTextureRegistry.unregister(id)
        }
    }

    @Test
    fun `complete frame uploads reuse two bounded direct buffers after consumption`() {
        val id = KoolCanvasTextureId("slick-complete-frame")
        val source = intArrayOf(0xff112233.toInt())
        try {
            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 1, 1, source)
            val texture = KoolCanvasTextureRegistry.resolve(KoolCanvasTextureRef(id, 1, 1), KoolCanvasTextureFilter.Nearest)
            val first = (texture.uploadData as BufferedImageData2d).data

            texture.uploadData = null
            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 1, 1, intArrayOf(0xff445566.toInt()))
            val second = (texture.uploadData as BufferedImageData2d).data
            assertNotSame(first, second)

            texture.uploadData = null
            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 1, 1, intArrayOf(0xff778899.toInt()))
            val third = (texture.uploadData as BufferedImageData2d).data
            assertSame(first, third)
            assertContentEquals(
                listOf(0x77, 0x88, 0x99, 0xff),
                (third as Uint8Buffer).let { buffer -> List(buffer.capacity) { buffer[it].toInt() } },
            )
        } finally {
            KoolCanvasTextureRegistry.unregister(id)
        }
    }

    @Test
    fun `complete frame keeps only two reusable owned ARGB arrays`() {
        val id = KoolCanvasTextureId("slick-complete-frame")
        try {
            val source = intArrayOf(0x00112233)
            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 1, 1, source)
            val first = KoolCanvasTextureRegistry.argbImageView(id)!!.pixels
            source[0] = 0x00445566
            assertEquals(0xff112233.toInt(), first[0])

            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 1, 1, source)
            val second = KoolCanvasTextureRegistry.argbImageView(id)!!.pixels
            assertNotSame(first, second)

            KoolCanvasTextureRegistry.registerOpaqueArgb(id, 1, 1, intArrayOf(0x00778899))
            val third = KoolCanvasTextureRegistry.argbImageView(id)!!.pixels
            assertSame(first, third)
            assertEquals(0xff778899.toInt(), third[0])
        } finally {
            KoolCanvasTextureRegistry.unregister(id)
        }
    }

    @Test
    fun `alpha bleed is opt in so backend generated textures skip the per update pass`() {
        val bleededId = KoolCanvasTextureId("argb-bleed-decoded-image")
        val rawId = KoolCanvasTextureId("argb-bleed-generated-texture")
        // One opaque red texel, one fully transparent texel, one opaque blue texel.
        val source = intArrayOf(0xffff0000.toInt(), 0x00000000, 0xff0000ff.toInt())
        try {
            KoolCanvasTextureRegistry.registerArgb(bleededId, 3, 1, source)
            assertEquals(
                listOf(0xffff0000.toInt(), 0x00ff0000, 0xff0000ff.toInt()),
                KoolCanvasTextureRegistry.argbImage(bleededId)!!.pixels.toList(),
            )

            KoolCanvasTextureRegistry.registerArgb(rawId, 3, 1, source, alphaBleed = false)
            assertEquals(
                listOf(0xffff0000.toInt(), 0x00000000, 0xff0000ff.toInt()),
                KoolCanvasTextureRegistry.argbImage(rawId)!!.pixels.toList(),
            )
        } finally {
            KoolCanvasTextureRegistry.unregister(bleededId)
            KoolCanvasTextureRegistry.unregister(rawId)
        }
    }
}
