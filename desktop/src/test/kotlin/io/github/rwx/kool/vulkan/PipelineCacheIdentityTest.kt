package io.github.rwx.kool.vulkan

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class PipelineCacheIdentityTest {
    private val uuid = ByteArray(16) { it.toByte() }
    private val identity = PipelineCacheIdentity(123, 456, 789, 10, uuid)
    private fun header() = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(32); putInt(1); putInt(123); putInt(456); put(uuid)
    }.array()

    @Test
    fun `valid cache header is accepted only for same device and UUID`() {
        assertTrue(identity.accepts(header()))
        assertFalse(PipelineCacheIdentity(321, 456, 789, 10, uuid).accepts(header()))
        assertFalse(PipelineCacheIdentity(123, 654, 789, 10, uuid).accepts(header()))
        assertFalse(PipelineCacheIdentity(123, 456, 789, 10, ByteArray(16)).accepts(header()))
    }

    @Test
    fun `driver and API versions isolate cache filenames`() {
        assertNotEquals(identity.fileKey, PipelineCacheIdentity(123, 456, 790, 10, uuid).fileKey)
        assertNotEquals(identity.fileKey, PipelineCacheIdentity(123, 456, 789, 11, uuid).fileKey)
    }

    @Test
    fun `truncated malformed and future version caches are discarded`() {
        assertFalse(identity.accepts(ByteArray(31)))
        assertFalse(identity.accepts(header().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 200) }))
        assertFalse(identity.accepts(header().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 2) }))
    }
}
