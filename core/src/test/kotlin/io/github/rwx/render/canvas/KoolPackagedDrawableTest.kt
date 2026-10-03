package io.github.rwx.render.canvas

import com.corrodinggames.rts.R
import com.corrodinggames.rts.gameFramework.graphics.RenderTargetMode
import io.github.rwx.geometry.Rect
import io.github.rwx.geometry.RectF
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class KoolPackagedDrawableTest {
    @Test
    fun `drawables outside the working directory produce rasterized fog targets`() {
        val originalDir = System.getProperty("user.dir")
        val emptyDir = Files.createTempDirectory("rwx-packaged-drawable").toFile()
        val pixels = IntArray(16 * 8) { 0xff112233.toInt() }
        val bytes = KoolGraphicsEngine.encodePng(16, 8, pixels)
        val reads = mutableListOf<String>()
        try {
            System.setProperty("user.dir", emptyDir.absolutePath)
            val graphics = KoolGraphicsEngine(KoolCanvasCpuTextureStore(), assetBytes = { path ->
                reads += path
                bytes.takeIf { path == "drawable/fog_smooth.png" }
            })
            val fog = graphics.a(R.drawable.fog_smooth, true)
            assertEquals(16, fog.width())
            assertEquals(8, fog.height())
            assertEquals(pixels.toList(), assertNotNull(fog.argbPixelsRef).toList())
            assertEquals(listOf("drawable/fog_smooth.png"), reads)

            val target = graphics.b(16, 8, true)
            val offscreen = graphics.b(target, RenderTargetMode.IMMEDIATE)
            offscreen.a(fog, Rect(0, 0, 16, 8), RectF(0f, 0f, 16f, 8f), null)
            offscreen.p()
            assertEquals(pixels.toList(), assertNotNull(target.argbPixelsRef).toList())
            fog.o()
            target.o()
        } finally {
            System.setProperty("user.dir", originalDir)
            emptyDir.delete()
        }
    }
}
