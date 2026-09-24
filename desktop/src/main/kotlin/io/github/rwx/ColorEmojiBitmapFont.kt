package io.github.rwx

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

/**
 * Reads color emoji out of an OpenType CBDT/CBLC font (Noto Color Emoji).
 * Java2D cannot paint that font's bitmaps, and Segoe UI Emoji falls back to
 * monochrome outlines, which is why flags showed up as "CN" / "UN".
 */
internal class ColorEmojiBitmapFont private constructor(
    private val cmap: Map<Int, Int>,
    private val ligatures: List<Map<Int, List<Ligature>>>,
    private val bitmapBytes: Map<Int, ByteArray>,
) {
    fun rasterize(emoji: String): BufferedImage? {
        val glyphs = ArrayList<Int>()
        var index = 0
        while (index < emoji.length) {
            val codePoint = emoji.codePointAt(index)
            glyphs += cmap[codePoint] ?: return null
            index += Character.charCount(codePoint)
        }
        applyLigatures(glyphs)
        if (glyphs.isEmpty()) return null
        val images = ArrayList<BufferedImage>(glyphs.size)
        for (glyph in glyphs) {
            val png = bitmapBytes[glyph] ?: return null
            images += ImageIO.read(ByteArrayInputStream(png)) ?: return null
        }
        if (images.size == 1) return images[0]
        val height = images.maxOf { it.height }
        val width = images.sumOf { it.width }
        val combined = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val graphics = combined.createGraphics()
        var x = 0
        for (image in images) {
            graphics.drawImage(image, x, (height - image.height) / 2, null)
            x += image.width
        }
        graphics.dispose()
        return combined
    }

    private fun applyLigatures(glyphs: MutableList<Int>) {
        for (lookup in ligatures) {
            var index = 0
            while (index < glyphs.size) {
                val set = lookup[glyphs[index]]
                val match = set?.firstOrNull { ligature ->
                    val end = index + 1 + ligature.components.size
                    end <= glyphs.size && glyphs.subList(index + 1, end) == ligature.components
                }
                if (match != null) {
                    val span = 1 + match.components.size
                    repeat(span) { glyphs.removeAt(index) }
                    glyphs.add(index, match.glyph)
                }
                index++
            }
        }
    }

    private data class Ligature(val glyph: Int, val components: List<Int>)

    companion object {
        fun load(file: File): ColorEmojiBitmapFont? = runCatching { parse(file.readBytes()) }.getOrNull()

        fun discover(): ColorEmojiBitmapFont? {
            for (path in CANDIDATE_PATHS) {
                val file = File(path)
                if (file.isFile) {
                    load(file)?.let { return it }
                }
            }
            return null
        }

        private val CANDIDATE_PATHS = listOf(
            "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf",
            "/usr/share/fonts/noto/NotoColorEmoji.ttf",
            "/usr/share/fonts/google-noto-emoji/NotoColorEmoji.ttf",
            System.getProperty("user.home") + "/.local/share/fonts/NotoColorEmoji.ttf",
        )

        private fun parse(bytes: ByteArray): ColorEmojiBitmapFont {
            val tables = tableDirectory(bytes)
            val cmap = parseCmap(slice(bytes, tables.getValue("cmap")))
            val ligatures = parseLigatures(slice(bytes, tables.getValue("GSUB")))
            val bitmaps = parseBitmaps(
                bytes = bytes,
                cblc = slice(bytes, tables.getValue("CBLC")),
                cbdtOffset = tables.getValue("CBDT").first,
            )
            return ColorEmojiBitmapFont(cmap, ligatures, bitmaps)
        }

        private fun tableDirectory(bytes: ByteArray): Map<String, Pair<Int, Int>> {
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            buffer.position(4)
            val count = buffer.short.toInt() and 0xffff
            buffer.position(12)
            val tables = HashMap<String, Pair<Int, Int>>(count)
            repeat(count) {
                val tag = ByteArray(4).also { buffer.get(it) }.decodeToString()
                buffer.int // checksum
                val offset = buffer.int
                val length = buffer.int
                tables[tag] = offset to length
            }
            return tables
        }

        private fun slice(bytes: ByteArray, span: Pair<Int, Int>): ByteArray =
            bytes.copyOfRange(span.first, span.first + span.second)

        private fun parseCmap(cmap: ByteArray): Map<Int, Int> {
            val buffer = ByteBuffer.wrap(cmap).order(ByteOrder.BIG_ENDIAN)
            buffer.short
            val tables = buffer.short.toInt() and 0xffff
            var format12 = -1
            repeat(tables) {
                buffer.short
                buffer.short
                val offset = buffer.int
                val format = ByteBuffer.wrap(cmap, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
                if (format == 12) format12 = offset
            }
            check(format12 >= 0) { "emoji font has no cmap format 12" }
            val groups = ByteBuffer.wrap(cmap, format12, cmap.size - format12).order(ByteOrder.BIG_ENDIAN)
            groups.short
            groups.short
            groups.int
            groups.int
            val count = groups.int
            val map = HashMap<Int, Int>(count * 8)
            repeat(count) {
                val start = groups.int
                val end = groups.int
                val glyph = groups.int
                var codePoint = start
                var glyphId = glyph
                while (codePoint <= end) {
                    map[codePoint] = glyphId
                    codePoint++
                    glyphId++
                }
            }
            return map
        }

        private fun parseLigatures(gsub: ByteArray): List<Map<Int, List<Ligature>>> {
            val buffer = ByteBuffer.wrap(gsub).order(ByteOrder.BIG_ENDIAN)
            buffer.short
            buffer.short
            buffer.short // script list
            val featureList = u16(buffer)
            val lookupList = u16(buffer)
            val featureCount = u16at(gsub, featureList)
            val lookupIndexes = mutableListOf<Int>()
            for (index in 0 until featureCount) {
                val record = featureList + 2 + index * 6
                val featureOffset = u16at(gsub, record + 4)
                val feature = featureList + featureOffset
                val lookupCount = u16at(gsub, feature + 2)
                for (lookup in 0 until lookupCount) {
                    lookupIndexes += u16at(gsub, feature + 4 + lookup * 2)
                }
            }
            return lookupIndexes.mapNotNull { index ->
                val lookupOffset = lookupList + u16at(gsub, lookupList + 2 + index * 2)
                ligatureLookup(gsub, lookupOffset)
            }
        }

        private fun ligatureLookup(gsub: ByteArray, lookupOffset: Int): Map<Int, List<Ligature>>? {
            var type = u16at(gsub, lookupOffset)
            var body = lookupOffset
            if (type == 6) {
                val subtable = lookupOffset + u16at(gsub, lookupOffset + 6)
                val extensionType = u16at(gsub, subtable + 2)
                val extensionOffset = u32at(gsub, subtable + 4)
                if (extensionType != 4) return null
                type = 4
                body = subtable + extensionOffset
                // Synthetic lookup header is not present; the extension points at the ligature subtable.
                return ligatureSubtable(gsub, body)
            }
            if (type != 4) return null
            val subtable = lookupOffset + u16at(gsub, lookupOffset + 6)
            return ligatureSubtable(gsub, subtable)
        }

        private fun ligatureSubtable(gsub: ByteArray, subtable: Int): Map<Int, List<Ligature>> {
            val coverage = coverageGlyphs(gsub, subtable + u16at(gsub, subtable + 2))
            val setCount = u16at(gsub, subtable + 4)
            val result = HashMap<Int, List<Ligature>>(setCount)
            for (index in 0 until setCount) {
                val set = subtable + u16at(gsub, subtable + 6 + index * 2)
                val ligatureCount = u16at(gsub, set)
                val ligatures = ArrayList<Ligature>(ligatureCount)
                for (ligatureIndex in 0 until ligatureCount) {
                    val ligature = set + u16at(gsub, set + 2 + ligatureIndex * 2)
                    val glyph = u16at(gsub, ligature)
                    val componentCount = u16at(gsub, ligature + 2)
                    val components = ArrayList<Int>(componentCount - 1)
                    for (component in 0 until componentCount - 1) {
                        components += u16at(gsub, ligature + 4 + component * 2)
                    }
                    ligatures += Ligature(glyph, components)
                }
                ligatures.sortByDescending { it.components.size }
                val first = coverage.getOrNull(index) ?: continue
                result[first] = ligatures
            }
            return result
        }

        private fun coverageGlyphs(gsub: ByteArray, coverage: Int): List<Int> {
            val format = u16at(gsub, coverage)
            if (format == 1) {
                val count = u16at(gsub, coverage + 2)
                return List(count) { u16at(gsub, coverage + 4 + it * 2) }
            }
            val rangeCount = u16at(gsub, coverage + 2)
            val glyphs = mutableListOf<Int>()
            for (index in 0 until rangeCount) {
                val record = coverage + 4 + index * 6
                val start = u16at(gsub, record)
                val end = u16at(gsub, record + 2)
                for (glyph in start..end) glyphs += glyph
            }
            return glyphs
        }

        private fun parseBitmaps(bytes: ByteArray, cblc: ByteArray, cbdtOffset: Int): Map<Int, ByteArray> {
            val numSizes = u32at(cblc, 4)
            val images = HashMap<Int, ByteArray>()
            for (sizeIndex in 0 until numSizes) {
                val size = 8 + sizeIndex * 48
                val arrayOffset = u32at(cblc, size)
                val subtableCount = u32at(cblc, size + 8)
                for (subIndex in 0 until subtableCount) {
                    val entry = arrayOffset + subIndex * 8
                    val first = u16at(cblc, entry)
                    val last = u16at(cblc, entry + 2)
                    val subtable = arrayOffset + u32at(cblc, entry + 4)
                    val indexFormat = u16at(cblc, subtable)
                    val imageFormat = u16at(cblc, subtable + 2)
                    val dataOffset = u32at(cblc, subtable + 4)
                    if (indexFormat != 1 || imageFormat != 17) continue
                    for (glyph in first..last) {
                        val slot = glyph - first
                        val start = u32at(cblc, subtable + 8 + slot * 4)
                        val end = u32at(cblc, subtable + 8 + (slot + 1) * 4)
                        val blobStart = cbdtOffset + dataOffset + start
                        if (end <= start || blobStart + 9 > bytes.size) continue
                        val pngLength = u32at(bytes, blobStart + 5)
                        val pngStart = blobStart + 9
                        if (pngLength <= 0 || pngStart + pngLength > bytes.size) continue
                        images[glyph] = bytes.copyOfRange(pngStart, pngStart + pngLength)
                    }
                }
            }
            return images
        }

        private fun u16(buffer: ByteBuffer): Int = buffer.short.toInt() and 0xffff

        private fun u16at(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

        private fun u32at(bytes: ByteArray, offset: Int): Int =
            ((bytes[offset].toInt() and 0xff) shl 24) or
                ((bytes[offset + 1].toInt() and 0xff) shl 16) or
                ((bytes[offset + 2].toInt() and 0xff) shl 8) or
                (bytes[offset + 3].toInt() and 0xff)
    }
}
