package io.github.rwx

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.github.rwx.ui.emoji.EmojiRaster
import io.github.rwx.ui.emoji.EmojiRasterizer
import kotlin.math.ceil

/** Rasterizes emoji with the platform Typeface (Noto Color Emoji on most devices). */
class AndroidEmojiRasterizer : EmojiRasterizer {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.LEFT
        isFilterBitmap = true
    }

    override fun rasterize(emoji: String, sizePx: Int): EmojiRaster? {
        paint.textSize = sizePx.toFloat()
        val pad = 2f
        val width = ceil(paint.measureText(emoji) + pad * 2).toInt().coerceAtLeast(1)
        val metrics = paint.fontMetrics
        val height = ceil(metrics.descent - metrics.ascent + pad * 2).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val baseline = pad - metrics.ascent
        canvas.drawText(emoji, pad, baseline, paint)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        bitmap.recycle()

        return pixels.toTightRgbaRaster(width, height)
    }
}

private fun IntArray.toTightRgbaRaster(width: Int, height: Int, alphaThreshold: Int = 8): EmojiRaster? {
    var minX = width
    var minY = height
    var maxX = -1
    var maxY = -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            val a = (this[y * width + x] ushr 24) and 0xff
            if (a > alphaThreshold) {
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
            val argb = this[y * width + x]
            rgba[index++] = ((argb ushr 16) and 0xff).toByte()
            rgba[index++] = ((argb ushr 8) and 0xff).toByte()
            rgba[index++] = (argb and 0xff).toByte()
            rgba[index++] = ((argb ushr 24) and 0xff).toByte()
        }
    }
    return EmojiRaster(width = outW, height = outH, rgba = rgba)
}
