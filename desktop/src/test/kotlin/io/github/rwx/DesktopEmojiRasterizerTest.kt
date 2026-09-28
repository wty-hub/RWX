package io.github.rwx

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopEmojiRasterizerTest {
    @Test
    fun `desktop emoji rasterizer produces pixels for common lobby symbols`() {
        val rasterizer = DesktopEmojiRasterizer()
        for (emoji in listOf("🍎", "🎮", "🔥", "🇨🇳", "⚔️", "🏆")) {
            val raster = assertNotNull(rasterizer.rasterize(emoji, 18), emoji)
            assertTrue(raster.width > 0 && raster.height > 0, emoji)
            val visiblePixels = (0 until raster.width * raster.height).count { pixel ->
                (raster.rgba[pixel * 4 + 3].toInt() and 0xff) >= 16
            }
            assertTrue(visiblePixels > 20, "$emoji rendered blank")
            val colorfulPixels = (0 until raster.width * raster.height).count { pixel ->
                val offset = pixel * 4
                val red = raster.rgba[offset].toInt() and 0xff
                val green = raster.rgba[offset + 1].toInt() and 0xff
                val blue = raster.rgba[offset + 2].toInt() and 0xff
                val alpha = raster.rgba[offset + 3].toInt() and 0xff
                alpha >= 16 && (kotlin.math.abs(red - green) > 12 || kotlin.math.abs(green - blue) > 12)
            }
            if (emoji == "🍎" || emoji == "🇨🇳") {
                assertTrue(colorfulPixels > 20, "$emoji rendered without color")
            }
        }
    }
}
