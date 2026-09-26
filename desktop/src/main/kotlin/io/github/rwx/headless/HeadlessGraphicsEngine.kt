package io.github.rwx.headless

import com.corrodinggames.rts.R
import com.corrodinggames.rts.gameFramework.graphics.GraphicsEngine
import com.corrodinggames.rts.gameFramework.graphics.ShaderProgram
import com.corrodinggames.rts.gameFramework.graphics.Texture
import com.corrodinggames.rts.gameFramework.utility.AssetInputStream
import io.github.rwx.PlatformStorage
import io.github.rwx.geometry.Rect
import io.github.rwx.geometry.RectF
import io.github.rwx.render.canvas.KoolCanvasBlendMode
import io.github.rwx.render.canvas.KoolPaint
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.locks.Lock
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * CPU graphics backend for headless simulation. Source images keep their real size and ARGB pixels
 * so team-color generation can read them. Draw and render-target calls do not paint.
 */
internal class HeadlessGraphicsEngine private constructor(
    private val storage: PlatformStorage,
    private val target: Texture?,
    private val decodedImages: MutableMap<String, DecodedImage>,
) : GraphicsEngine {
    constructor(storage: PlatformStorage) : this(storage, null, mutableMapOf())
    private var width: Int = target?.p?.coerceAtLeast(1) ?: 1
    private var height: Int = target?.q?.coerceAtLeast(1) ?: 1
    private val fallbackTexture: Texture by lazy { blankTexture(1, 1, "fallback") }

    override fun b(texture: Texture?): GraphicsEngine =
        HeadlessGraphicsEngine(storage, texture, decodedImages)

    override fun a(lock: Lock?) {
        lock?.lock()
    }

    override fun b(lock: Lock?) {
        lock?.unlock()
    }

    override fun a(i: Int): Texture = a(i, true)

    override fun a(i: Int, z: Boolean): Texture {
        val name = drawableNamesById[i] ?: return fallbackTexture
        val path = drawableAssetPath(name) ?: return fallbackTexture
        val bytes = storage.readAssetBytes(path) ?: return fallbackTexture
        return textureFromBytes(bytes, "drawable/$name")
    }

    override fun a(inputStream: InputStream?, z: Boolean): Texture {
        val sourceKey = (inputStream as? AssetInputStream)?.getPath()
        val bytes = inputStream?.readBytes() ?: return fallbackTexture
        return textureFromBytes(bytes, sourceKey ?: "stream/${System.identityHashCode(inputStream)}")
    }

    override fun a(i: Int, i2: Int, z: Boolean): Texture = blankTexture(i, i2, null)

    override fun b(i: Int, i2: Int, z: Boolean): Texture = blankTexture(i, i2, null)

    override fun a(texture: Texture?, f: Float, f2: Float, f3: Float, paint: KoolPaint?) = Unit

    override fun a(texture: Texture?, rect: Rect?, f: Float, f2: Float, f3: Float, paint: KoolPaint?) = Unit

    override fun a(texture: Texture?, rect: Rect?, rect2: Rect?, paint: KoolPaint?) = Unit

    override fun a(texture: Texture?, rect: Rect?, rectF: RectF?, paint: KoolPaint?) = Unit

    override fun a(texture: Texture?, f: Float, f2: Float, paint: KoolPaint?) = Unit

    override fun a(texture: Texture?, f: Float, f2: Float, paint: KoolPaint?, f3: Float, f4: Float) = Unit

    override fun b(texture: Texture?, f: Float, f2: Float, paint: KoolPaint?) = Unit

    override fun b(texture: Texture?, rect: Rect?, rect2: Rect?, paint: KoolPaint?) = Unit

    override fun a(rect: Rect?, paint: KoolPaint?) = Unit

    override fun a(texture: Texture?, rect: Rect?, paint: KoolPaint?) = Unit

    override fun a(texture: Texture?, rect: Rect?, paint: KoolPaint?, i: Int, i2: Int, i3: Int, i4: Int) = Unit

    override fun a(texture: Texture?, rectF: RectF?, paint: KoolPaint?, f: Float, f2: Float, i: Int, i2: Int) = Unit

    override fun b(i: Int) = Unit

    override fun a(i: Int, mode: KoolCanvasBlendMode?) = Unit

    override fun a(str: String?, f: Float, f2: Float, paint: KoolPaint?, paint2: KoolPaint?, f3: Float) = Unit

    override fun a(str: String?, f: Float, f2: Float, paint: KoolPaint?) = Unit

    override fun b(rect: Rect?, paint: KoolPaint?) = Unit

    override fun a(z: Boolean) = Unit

    override fun f() = Unit

    override fun a(rectF: RectF?, paint: KoolPaint?) = Unit

    override fun c(rect: Rect?, paint: KoolPaint?) = Unit

    override fun a(rect: Rect?) = Unit

    override fun a(rectF: RectF?) = Unit

    override fun a(f: Float, f2: Float, f3: Float, paint: KoolPaint?) = Unit

    override fun b(f: Float, f2: Float, f3: Float, paint: KoolPaint?) = Unit

    override fun a(fArr: FloatArray?, i: Int, i2: Int, paint: KoolPaint?) = Unit

    override fun i() = Unit

    override fun j() = Unit

    override fun k() = Unit

    override fun l() = Unit

    override fun a(f: Float, f2: Float, f3: Float) = Unit

    override fun a(f: Float, f2: Float) = Unit

    override fun a(f: Float, f2: Float, f3: Float, f4: Float) = Unit

    override fun b(f: Float, f2: Float) = Unit

    override fun a(f: Float, f2: Float, f3: Float, f4: Float, paint: KoolPaint?) = Unit

    override fun m(): Int = target?.p?.coerceAtLeast(1) ?: width

    override fun n(): Int = target?.q?.coerceAtLeast(1) ?: height

    override fun a(i: Int, i2: Int) {
        width = i.coerceAtLeast(1)
        height = i2.coerceAtLeast(1)
    }

    override fun o() = Unit

    override fun p() = Unit

    override fun q() = Unit

    override fun a(shaderProgram: ShaderProgram?) = Unit

    override fun a(str: String?, paint: KoolPaint?): Int =
        paint?.k()?.roundToInt()?.coerceAtLeast(1) ?: 16

    override fun b(str: String?, paint: KoolPaint?): Int {
        val size = paint?.k()?.roundToInt()?.coerceAtLeast(1) ?: 16
        return (str?.length ?: 0) * size / 2
    }

    override fun r(): Texture = fallbackTexture

    override fun a(texture: Texture?, file: File?) {
        val source = texture ?: return
        val targetFile = file ?: return
        val pixels = source.argbPixelsCopy ?: return
        val imageWidth = source.p.coerceAtLeast(1)
        val imageHeight = source.q.coerceAtLeast(1)
        if (pixels.size < imageWidth * imageHeight) return
        val image = BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, imageWidth, imageHeight, pixels, 0, imageWidth)
        targetFile.parentFile?.mkdirs()
        ImageIO.write(image, "png", targetFile)
    }

    private fun textureFromBytes(bytes: ByteArray, sourceKey: String): Texture {
        val cached = decodedImages[sourceKey]
        if (cached != null) {
            return textureFromDecoded(cached, sourceKey)
        }
        val decoded = decodeImage(bytes) ?: return fallbackTexture
        decodedImages[sourceKey] = decoded
        return textureFromDecoded(decoded, sourceKey)
    }

    private fun drawableAssetPath(drawableName: String): String? =
        imageFileNames(drawableName)
            .map { "drawable/$it" }
            .firstOrNull(storage::assetFileExists)
            ?: imageFileNames(drawableName)
                .map { "assets/drawable/$it" }
                .firstOrNull(storage::assetFileExists)

    private class DecodedImage(
        val width: Int,
        val height: Int,
        val pixels: IntArray,
    )

    private companion object {
        val drawableNamesById: Map<Int, String> by lazy {
            R.drawable::class.java.fields.associate { field -> field.getInt(null) to field.name }
        }

        fun imageFileNames(nameWithoutExtension: String): List<String> =
            listOf(
                "$nameWithoutExtension.png",
                "$nameWithoutExtension.9.png",
                "$nameWithoutExtension.jpg",
                "$nameWithoutExtension.jpeg",
            )

        fun blankTexture(width: Int, height: Int, sourceKey: String?): Texture {
            val texture = Texture()
            texture.p = width.coerceAtLeast(1)
            texture.q = height.coerceAtLeast(1)
            texture.updateCenter()
            sourceKey?.let(texture::setSourceName)
            texture.setCommittedArgbPixels(IntArray(texture.p * texture.q))
            return texture
        }

        fun decodeImage(bytes: ByteArray): DecodedImage? {
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
            val pixels = IntArray(image.width * image.height)
            for (y in 0 until image.height) {
                for (x in 0 until image.width) {
                    pixels[x + y * image.width] = image.getRGB(x, y)
                }
            }
            return DecodedImage(image.width, image.height, pixels)
        }

        fun textureFromDecoded(decoded: DecodedImage, sourceKey: String): Texture {
            val texture = Texture()
            texture.p = decoded.width.coerceAtLeast(1)
            texture.q = decoded.height.coerceAtLeast(1)
            texture.updateCenter()
            texture.setSourceName(sourceKey)
            texture.setCommittedArgbPixels(decoded.pixels)
            return texture
        }
    }
}
