package io.github.rwx.slick

import de.fabmax.kool.util.useRaw
import io.github.rwx.render.canvas.KoolCanvasOpaquePixelPacker
import java.nio.ByteOrder

/** Packs the owned, opaque ARGB frame into the RGBA bytes expected by Kool's texture loader. */
internal val desktopOpaqueFramePacker = KoolCanvasOpaquePixelPacker { destination, sourceArgb, pixelCount ->
    require(sourceArgb.size >= pixelCount && destination.capacity >= pixelCount * 4)
    destination.useRaw { bytes ->
        val rgba = bytes.order(ByteOrder.BIG_ENDIAN).asIntBuffer()
        for (index in 0 until pixelCount) {
            rgba.put(Integer.rotateLeft(sourceArgb[index], 8))
        }
    }
}
