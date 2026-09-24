package io.github.rwx

import io.github.rwx.ui.emoji.EmojiRaster
import io.github.rwx.ui.emoji.EmojiRasterizer
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.roundToInt

/**
 * Rasterizes emoji with AWT. Prefers fonts that actually paint pixels (Segoe UI Emoji works;
 * Noto Color Emoji's CBDT tables usually do not under Java2D).
 */
class DesktopEmojiRasterizer : EmojiRasterizer {
    @Volatile
    private var resolvedFont: Font? = null

    override fun rasterize(emoji: String, sizePx: Int): EmojiRaster? {
        colorEmoji()?.rasterize(emoji)?.let { source ->
            return scale(source, sizePx).toTightRgbaRaster()
        }
        val font = emojiFont(sizePx) ?: return null
        val pad = 2
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val probeG = probe.createGraphics().also { configure(it, font) }
        val metrics = probeG.fontMetrics
        val width = (metrics.stringWidth(emoji) + pad * 2).coerceAtLeast(1)
        val height = (metrics.height + pad * 2).coerceAtLeast(1)
        probeG.dispose()

        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics().also { configure(it, font) }
        // Color emoji fonts ignore the paint color and embed their own; monochrome emoji fonts
        // need an opaque foreground. White reads well on the dark RWX chrome.
        graphics.color = Color.WHITE
        val baseline = pad + metrics.ascent
        graphics.drawString(emoji, pad, baseline)
        graphics.dispose()

        return image.toTightRgbaRaster()
    }

    private fun configure(graphics: Graphics2D, font: Font) {
        graphics.font = font
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    }

    private fun emojiFont(sizePx: Int): Font? {
        val base = resolvedFont ?: discoverEmojiFont()?.also { resolvedFont = it } ?: return null
        return base.deriveFont(sizePx.toFloat())
    }

    private fun discoverEmojiFont(): Font? {
        for (path in CANDIDATE_FONT_FILES) {
            val file = File(path)
            if (!file.isFile) continue
            val font = runCatching {
                Font.createFont(Font.TRUETYPE_FONT, file)
            }.getOrNull() ?: continue
            if (fontPaintsEmoji(font.deriveFont(48f))) return font
        }
        for (name in CANDIDATE_FONT_NAMES) {
            val font = Font(name, Font.PLAIN, 48)
            if (font.family.equals(name, ignoreCase = true) && fontPaintsEmoji(font)) {
                return font
            }
        }
        for (font in GraphicsEnvironment.getLocalGraphicsEnvironment().allFonts) {
            val sized = font.deriveFont(48f)
            if (sized.canDisplay(0x1F34E) && fontPaintsEmoji(sized)) {
                return font
            }
        }
        val dialog = Font(Font.DIALOG, Font.PLAIN, 48)
        return dialog.takeIf { fontPaintsEmoji(it) }
    }

    private fun fontPaintsEmoji(font: Font): Boolean {
        if (!font.canDisplay(0x1F34E) && !font.canDisplay(0x1F600)) return false
        val image = BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics().also { configure(it, font) }
        graphics.color = Color.WHITE
        graphics.drawString("\uD83C\uDF4E", 4, 48) // 🍎
        graphics.dispose()
        return image.countOpaquePixels() > 20
    }

    private fun scale(source: BufferedImage, sizePx: Int): BufferedImage {
        val targetHeight = sizePx.coerceAtLeast(1)
        val targetWidth = (source.width.toFloat() / source.height * targetHeight).roundToInt().coerceAtLeast(1)
        if (source.width == targetWidth && source.height == targetHeight) return source
        val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        graphics.drawImage(source, 0, 0, targetWidth, targetHeight, null)
        graphics.dispose()
        return scaled
    }

    private companion object {
        @Volatile
        private var colorFont: ColorEmojiBitmapFont? = null

        @Volatile
        private var colorFontResolved: Boolean = false

        fun colorEmoji(): ColorEmojiBitmapFont? {
            if (!colorFontResolved) {
                colorFont = ColorEmojiBitmapFont.discover()
                colorFontResolved = true
            }
            return colorFont
        }

        val CANDIDATE_FONT_NAMES = listOf(
            "Segoe UI Emoji",
            "Apple Color Emoji",
            "EmojiOne Color",
            "Twemoji Mozilla",
            "Noto Emoji",
        )
        val CANDIDATE_FONT_FILES = listOf(
            System.getProperty("user.home") + "/.local/share/fonts/ImportedFonts/seguiemj.ttf",
            "/usr/share/fonts/truetype/noto/NotoEmoji-Regular.ttf",
            "C:/Windows/Fonts/seguiemj.ttf",
            "/System/Library/Fonts/Apple Color Emoji.ttc",
        )
    }
}

private fun BufferedImage.countOpaquePixels(alphaThreshold: Int = 8): Int {
    var opaque = 0
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (((getRGB(x, y) ushr 24) and 0xff) > alphaThreshold) opaque++
        }
    }
    return opaque
}

/** Crops to the opaque ink bounds so UI layout uses a tight aspect ratio. */
private fun BufferedImage.toTightRgbaRaster(alphaThreshold: Int = 8): EmojiRaster? {
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            if (((getRGB(x, y) ushr 24) and 0xff) > alphaThreshold) {
                if (x < minX) minX = x
                if (y < minY) minY = y
                if (x > maxX) maxX = x
                if (y > maxY) maxY = y
            }
        }
    }
    if (maxX < minX || maxY < minY) return null
    val pad = 1
    val left = (minX - pad).coerceAtLeast(0)
    val top = (minY - pad).coerceAtLeast(0)
    val right = (maxX + pad).coerceAtMost(width - 1)
    val bottom = (maxY + pad).coerceAtMost(height - 1)
    val outW = right - left + 1
    val outH = bottom - top + 1
    val rgba = ByteArray(outW * outH * 4)
    var index = 0
    for (y in top..bottom) {
        for (x in left..right) {
            val argb = getRGB(x, y)
            rgba[index++] = ((argb ushr 16) and 0xff).toByte()
            rgba[index++] = ((argb ushr 8) and 0xff).toByte()
            rgba[index++] = (argb and 0xff).toByte()
            rgba[index++] = ((argb ushr 24) and 0xff).toByte()
        }
    }
    return EmojiRaster(width = outW, height = outH, rgba = rgba)
}
