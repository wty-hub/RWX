package io.github.rwx

import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ColorEmojiBitmapFontTest {
    @Test
    fun `noto color emoji paints flags and pictographs in color`() {
        val fontFile = File("/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf")
        if (!fontFile.isFile) return
        val font = ColorEmojiBitmapFont.load(fontFile)
        assertNotNull(font)
        for (emoji in listOf("🍎", "🚀", "🎮", "🇨🇳", "🇺🇳")) {
            val image = font!!.rasterize(emoji)
            assertNotNull(image, emoji)
            assertTrue(image.width > 8 && image.height > 8, emoji)
            assertTrue(coloredPixels(image) > 20, emoji)
        }
    }

    private fun coloredPixels(image: java.awt.image.BufferedImage): Int {
        var colored = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                val a = (argb ushr 24) and 0xff
                if (a < 16) continue
                val r = (argb ushr 16) and 0xff
                val g = (argb ushr 8) and 0xff
                val b = argb and 0xff
                if (kotlin.math.abs(r - g) > 12 || kotlin.math.abs(g - b) > 12) colored++
            }
        }
        return colored
    }
}
