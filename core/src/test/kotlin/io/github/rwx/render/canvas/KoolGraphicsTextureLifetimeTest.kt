package io.github.rwx.render.canvas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoolGraphicsTextureLifetimeTest {
    @Test
    fun `repeated legacy texture release removes CPU pixels and revision records while a completed packet remains valid`() {
        val store = KoolCanvasCpuTextureStore()
        val graphics = KoolGraphicsEngine(textureStore = store)
        val registrations = KoolGraphicsEngine::class.java
            .getDeclaredField("registeredTexturePixelRevisions")
            .apply { isAccessible = true }
            .get(graphics) as Map<*, *>
        val sources = KoolCanvasCpuTextureStore::class.java
            .getDeclaredField("sources")
            .apply { isAccessible = true }
            .get(store) as Map<*, *>

        repeat(1000) { index ->
            graphics.beginFrame(32, 32)
            val color = 0xff000000.toInt() or index
            val texture = graphics.a(2, 2, true).apply { j = IntArray(4) { color } }
            graphics.a(texture, 0f, 0f, null)
            val frame = graphics.snapshot()
            val logicalId = (frame.commands.single() as KoolCanvasCommand.DrawTexture).texture.id
            val packet = store.freezeFrame(frame, index.toLong(), 1L, index, 1L)
            try {
                assertEquals(1, registrations.size)
                assertEquals(1, sources.size)
                texture.o()
                // Release is allowed before the delayed renderer sees the completed packet.
                assertEquals(0, registrations.size, "Released texture $index retained registration metadata")
                assertEquals(0, sources.size, "Released texture $index retained its logical CPU pixels")
                assertNull(store.argbImageView(logicalId))
                assertEquals(1, packet.resourceLease.resourceCount)
                val frozen = packet.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
                assertTrue(frozen.image.pixels.all { it == color })
                texture.o() // Original resource cleanup can release the same texture twice.
                assertEquals(0, registrations.size)
            } finally {
                texture.o()
                packet.close()
            }
            assertTrue(packet.resourceLease.isReleased)
        }
    }
}
