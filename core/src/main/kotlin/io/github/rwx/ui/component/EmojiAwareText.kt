package io.github.rwx.ui.component

import de.fabmax.kool.AssetLoader
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.pipeline.BufferedImageData2d
import de.fabmax.kool.pipeline.TexFormat
import de.fabmax.kool.pipeline.Texture2d
import de.fabmax.kool.util.Color
import de.fabmax.kool.util.Font
import de.fabmax.kool.util.Uint8Buffer
import io.github.rwx.ui.emoji.EmojiRasterizerBridge
import io.github.rwx.ui.emoji.TextRun
import io.github.rwx.ui.emoji.containsEmoji
import io.github.rwx.ui.emoji.toTextRuns
import kotlin.math.roundToInt

/**
 * Text that keeps emoji visible. The CJK MSDF atlas has no emoji glyphs; missing code points are
 * rasterized through [EmojiRasterizerBridge] into small RGBA textures and inlined as images.
 */
fun UiScope.EmojiAwareText(
    text: String,
    textFont: Font,
    textColor: Color,
    contentWidth: Dimension = Grow.Std,
    contentHeight: Dp? = null,
    alignX: AlignmentX = AlignmentX.Start,
    alignY: AlignmentY = AlignmentY.Center,
    wrap: Boolean = false,
    clip: Boolean = false,
) {
    if (!text.containsEmoji() || !EmojiRasterizerBridge.isAvailable()) {
        Text(text) {
            modifier
                .width(contentWidth)
                .font(textFont)
                .textColor(textColor)
                .textAlign(alignX, alignY)
                .isWrapText(wrap)
            contentHeight?.let { modifier.height(it) }
            if (clip) modifier.clipToBounds(true)
        }
        return
    }

    val runs = text.toTextRuns()
    if (runs.none { it is TextRun.Emoji }) {
        Text(text) {
            modifier
                .width(contentWidth)
                .font(textFont)
                .textColor(textColor)
                .textAlign(alignX, alignY)
                .isWrapText(wrap)
            contentHeight?.let { modifier.height(it) }
            if (clip) modifier.clipToBounds(true)
        }
        return
    }

    // A Row cannot honor '\n'. Chat lines (rankings, system help) embed newlines next to emoji,
    // so each line is its own row.
    Column(width = contentWidth) {
        contentHeight?.let { modifier.height(it) }
        modifier.alignY(alignY)
        val lines = text.split('\n')
        for (line in lines) {
            if (line.isEmpty()) {
                Box(width = Grow.Std, height = Dp(textFont.sizePts)) {}
            } else {
                emojiLine(
                    line = line,
                    textFont = textFont,
                    textColor = textColor,
                    alignY = alignY,
                    contentHeight = if (lines.size == 1) contentHeight else null,
                )
            }
        }
    }
}

private fun UiScope.emojiLine(
    line: String,
    textFont: Font,
    textColor: Color,
    alignY: AlignmentY,
    contentHeight: Dp?,
) {
    Row(width = Grow.Std) {
        contentHeight?.let { modifier.height(it) }
        modifier.alignY(alignY)
        val emojiHeight = Dp(textFont.sizePts)
        for (run in line.toTextRuns()) {
            when (run) {
                is TextRun.Plain -> Text(run.value) {
                    modifier
                        .alignY(alignY)
                        .font(textFont)
                        .textColor(textColor)
                        .textAlign(AlignmentX.Start, alignY)
                        .isWrapText(false)
                    contentHeight?.let { modifier.height(it) }
                }

                is TextRun.Emoji -> {
                    val sizePx = textFont.sizePts.roundToInt().coerceIn(12, 96)
                    val cached = EmojiTextureCache.entryFor(run.value, sizePx)
                    val width = Dp(textFont.sizePts * cached.aspectRatio)
                    Image(cached.texture) {
                        modifier
                            .width(width)
                            .height(emojiHeight)
                            .alignY(alignY)
                            .imageSize(ImageSize.Stretch)
                    }
                }
            }
        }
    }
}

private data class EmojiTextureEntry(
    val texture: Texture2d,
    val aspectRatio: Float,
)

private object EmojiTextureCache {
    private val entries = mutableMapOf<String, EmojiTextureEntry>()

    fun entryFor(emoji: String, sizePx: Int): EmojiTextureEntry {
        val key = "$sizePx:$emoji"
        return entries.getOrPut(key) {
            val raster = EmojiRasterizerBridge.rasterize(emoji, sizePx)
            val aspect = if (raster != null && raster.height > 0) {
                (raster.width.toFloat() / raster.height.toFloat()).coerceIn(0.5f, 4f)
            } else {
                1f
            }
            val texture = Texture2d(name = "emoji:$key") {
                if (raster == null) return@Texture2d AssetLoader.textureDataLoadFailed
                val pixels = Uint8Buffer(raster.rgba.size)
                for (index in raster.rgba.indices) {
                    pixels[index] = raster.rgba[index].toUByte()
                }
                BufferedImageData2d(
                    data = pixels,
                    width = raster.width,
                    height = raster.height,
                    format = TexFormat.RGBA,
                    id = "emoji:$key",
                )
            }
            EmojiTextureEntry(texture = texture, aspectRatio = aspect)
        }
    }
}
