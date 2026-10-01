package io.github.rwx.render.canvas

import com.corrodinggames.rts.game.units.BaseUnit
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KoolGraphicsTextureLifetimeTest {
    @Test
    fun `ship shadow retains source alpha after team coloring releases editable pixels`() {
        val shipImage = sequenceOf(File("assets/drawable/battle_ship_t2.png"),
            File("../assets/drawable/battle_ship_t2.png")).first { it.isFile }
        val store = KoolCanvasCpuTextureStore()
        val graphics = KoolGraphicsEngine(textureStore = store)
        val source = shipImage.inputStream().use { graphics.a(it, true) }
        val original = source.argbPixelsCopy!!
        assertTrue(original.any { it ushr 24 != 0 })
        // PlayerTeam's CPU coloring path opens the editable buffer and releases it afterwards.
        source.j()
        source.r()
        val shadow = BaseUnit.attackUnit(source, source.width(), source.height())
        val expected = original.map { it and 0xff000000.toInt() }
        assertEquals(expected, shadow.argbPixelsCopy!!.toList())

        graphics.beginFrame(128, 128)
        graphics.a(shadow, 0f, 0f, null)
        val frame = graphics.snapshot()
        val packet = store.freezeFrame(frame, 1L, 1L, 1, 1L)
        try {
            val frozen = packet.resourceLease.resources().values.single() as FrozenCanvasResource.Pixels
            assertEquals(expected, frozen.image.pixels.toList())
        } finally {
            packet.close()
            source.o()
            shadow.o()
        }
    }

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
