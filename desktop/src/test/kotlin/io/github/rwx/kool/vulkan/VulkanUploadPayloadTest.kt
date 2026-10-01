package io.github.rwx.kool.vulkan

import de.fabmax.kool.pipeline.*
import de.fabmax.kool.util.Float32Buffer
import de.fabmax.kool.util.Uint8Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class VulkanUploadPayloadTest {
    @Test
    fun `cube faces append in Vulkan layer order`() {
        fun face(value: Int) = BufferedImageData2d(Uint8Buffer(4).apply {
            repeat(4) { put(value.toByte()) }
        }, 1, 1, TexFormat.RGBA, "face-$value")
        val cube = ImageDataCube(negX = face(2), posX = face(1), negY = face(4), posY = face(3), negZ = face(6), posZ = face(5))
        val bytes = ByteArray(24)
        val target = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        VulkanUploads.copyImageData(cube, target)
        assertEquals(24, target.position())
        assertContentEquals(ByteArray(24) { (it / 4 + 1).toByte() }, bytes)
    }

    @Test
    fun `half float conversion writes actual image byte count`() {
        val data = BufferedImageData2d(Float32Buffer(4).apply {
            put(0f); put(1f); put(-1f); put(0.5f)
        }, 1, 1, TexFormat.RGBA_F16, "half")
        val bytes = ByteArray(8)
        val target = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        VulkanUploads.copyImageData(data, target)
        assertEquals(8, target.position())
        assertContentEquals(byteArrayOf(0, 0, 0, 0x3c, 0, 0xbc.toByte(), 0, 0x38), bytes)
    }

    @Test
    fun `texture byte size handles layers and rejects oversized payloads`() {
        assertEquals(512 * 512 * 4, VulkanUploads.checkedTextureBytes(512, 512, 1, 1, TexFormat.RGBA))
        assertEquals(6 * 16 * 16 * 8, VulkanUploads.checkedTextureBytes(16, 16, 1, 6, TexFormat.RGBA_F16))
        assertFailsWith<ArithmeticException> { VulkanUploads.checkedTextureBytes(65536, 65536, 1, 1, TexFormat.RGBA) }
    }
}
