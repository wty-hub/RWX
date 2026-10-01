package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.*
import de.fabmax.kool.util.Uint8Buffer

/** Renderer-owned atlas. Only immutable decoded pixels may enter it; generated targets never do. */
internal class KoolCanvasSpriteAtlas(
    private val textureStore: KoolCanvasTextureStore,
    private val pageSize: Int = 2048,
    private val maxPages: Int = 4,
) {
    internal data class Slot(val page: Page, val x: Int, val y: Int, val width: Int, val height: Int) {
        fun uv(source: KoolCanvasRect): KoolCanvasRect = KoolCanvasRect(
            (x + source.left) / page.size, (y + source.top) / page.size,
            (x + source.right) / page.size, (y + source.bottom) / page.size,
        )
    }

    internal class Page(val serial: Int, val size: Int) {
        val pixels = IntArray(size * size)
        var cursorX = 0
        var cursorY = 0
        var rowHeight = 0
        var dirty = false
        var lastUsed = 0L
        val textures = mutableMapOf<KoolCanvasTextureFilter, Texture2d>()
    }

    private val pages = mutableListOf<Page>()
    private val slots = mutableMapOf<KoolCanvasTextureId, Slot>()
    private var serial = 0
    private var frameNumber = 0L

    fun prepare(commands: List<KoolCanvasCommand>) {
        frameNumber++
        for (command in commands) {
            if (command is KoolCanvasCommand.DrawTexture && isSupported(command)) {
                slot(command)?.page?.lastUsed = frameNumber
            }
        }
        // Changing an atlas creates a new GPU owner. Never modify upload memory referenced by an
        // in-flight frame. Texture retirement waits for the renderer's GPU completion fence.
        for (page in pages) if (page.dirty) {
            page.textures.values.forEach { texture -> KoolCanvasGpuRetirement.retire { texture.release() } }
            page.textures.clear()
            page.dirty = false
        }
        val expired = pages.filter { frameNumber - it.lastUsed > 60 }
        if (expired.isNotEmpty()) {
            slots.entries.removeAll { it.value.page in expired }
            expired.forEach { page -> page.textures.values.forEach { texture -> KoolCanvasGpuRetirement.retire { texture.release() } } }
            pages.removeAll(expired.toSet())
        }
    }

    fun slot(command: KoolCanvasCommand.DrawTexture): Slot? {
        if (!isSupported(command)) return null
        slots[command.texture.id]?.let { return it }
        val image = textureStore.staticArgbImage(command.texture.id) ?: return null
        if (image.premultipliedAlpha || image.width != command.texture.width || image.height != command.texture.height ||
            image.width <= 0 || image.height <= 0 || image.width + 4 > pageSize || image.height + 4 > pageSize) return null
        val page = pages.firstOrNull { fits(it, image.width, image.height) }
            ?: if (pages.size < maxPages) Page(++serial, pageSize).also { pages += it } else return null
        val paddedWidth = image.width + 4
        val paddedHeight = image.height + 4
        if (page.cursorX + paddedWidth > pageSize) {
            page.cursorY += page.rowHeight
            page.cursorX = 0
            page.rowHeight = 0
        }
        val x = page.cursorX + 2
        val y = page.cursorY + 2
        // Repeat edge texels into two guard pixels, including corners, to preserve filtered edges.
        for (dy in -2 until image.height + 2) {
            val sy = dy.coerceIn(0, image.height - 1)
            for (dx in -2 until image.width + 2) {
                val sx = dx.coerceIn(0, image.width - 1)
                page.pixels[(y + dy) * pageSize + x + dx] = image.pixels[sy * image.width + sx]
            }
        }
        page.cursorX += paddedWidth
        page.rowHeight = maxOf(page.rowHeight, paddedHeight)
        page.dirty = true
        page.lastUsed = frameNumber
        return Slot(page, x, y, image.width, image.height).also { slots[command.texture.id] = it }
    }

    fun texture(page: Page, filter: KoolCanvasTextureFilter): Texture2d = page.textures.getOrPut(filter) {
        val data = Uint8Buffer(page.pixels.size * 4)
        for (pixel in page.pixels) {
            data.put(((pixel ushr 16) and 255).toUByte())
            data.put(((pixel ushr 8) and 255).toUByte())
            data.put((pixel and 255).toUByte())
            data.put((pixel ushr 24).toUByte())
        }
        data.position = 0
        val name = "rwx-sprite-atlas-${page.serial}-$frameNumber-$filter"
        Texture2d(
            data = BufferedImageData2d(data, pageSize, pageSize, TexFormat.RGBA, name),
            mipMapping = MipMapping.Off,
            samplerSettings = if (filter == KoolCanvasTextureFilter.Nearest)
                SamplerSettings().clamped().nearest().noAnisotropy()
            else SamplerSettings().clamped().linear().noAnisotropy(),
            name = name,
        )
    }

    private fun fits(page: Page, width: Int, height: Int): Boolean {
        val nextRow = if (page.cursorX + width + 4 > pageSize) page.cursorY + page.rowHeight else page.cursorY
        return nextRow + height + 4 <= pageSize
    }

    companion object {
        fun isSupported(command: KoolCanvasCommand.DrawTexture): Boolean =
            command.state.renderTarget == null && !command.texture.premultipliedAlpha &&
                (command.paint.textureEffect == null || command.paint.textureEffect is KoolCanvasTextureEffect.TeamColor) &&
                (command.paint.blendMode == KoolCanvasBlendMode.SourceOver ||
                    command.paint.blendMode == KoolCanvasBlendMode.Add || command.paint.blendMode == KoolCanvasBlendMode.Source) &&
                !command.source.hasZeroArea && !command.destination.hasZeroArea &&
                command.source.boundsLeft >= 0f && command.source.boundsTop >= 0f &&
                command.source.boundsRight <= command.texture.width && command.source.boundsBottom <= command.texture.height
    }
}
