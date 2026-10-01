package io.github.rwx.render.canvas

import de.fabmax.kool.pipeline.Texture2d
import io.github.rwx.session.GameCameraSnapshot
import java.util.Collections
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** A completed CPU frame. Each owner has its own closeable reference to the resource lease. */
class FrameEnvelope(
    val sequence: Long,
    val generation: Long,
    val simulationTick: Int,
    val viewportRevision: Long,
    val frame: KoolCanvasFrame,
    val resourceLease: KoolCanvasResourceLease,
    val camera: GameCameraSnapshot? = null,
) : AutoCloseable {
    private val closed = AtomicBoolean()

    /** Call only while holding an existing owner reference. */
    fun retain(): FrameEnvelope {
        check(!closed.get()) { "Cannot retain a released frame" }
        resourceLease.retain()
        return FrameEnvelope(sequence, generation, simulationTick, viewportRevision, frame, resourceLease, camera)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) resourceLease.close()
    }
}

/** publish transfers ownership; poll atomically transfers it to the consumer. */
class LatestFrameMailbox : AutoCloseable {
    private val pending = AtomicReference<FrameEnvelope?>()
    private val gate = Any()
    private var closed = false
    fun publish(frame: FrameEnvelope) {
        synchronized(gate) {
            if (closed) frame.close() else pending.getAndSet(frame)?.close()
        }
    }
    fun poll(): FrameEnvelope? = pending.getAndSet(null)
    fun clear() { pending.getAndSet(null)?.close() }
    override fun close() {
        synchronized(gate) { closed = true; clear() }
    }
}

/** Render-owned caches share the desktop Vulkan fence retirement policy when installed. */
object KoolCanvasGpuRetirement {
    @Volatile
    private var sink: ((() -> Unit) -> Unit)? = null
    internal fun install(sink: ((() -> Unit) -> Unit)?) { this.sink = sink }
    fun retire(release: () -> Unit) { sink?.invoke(release) ?: release() }
}

internal sealed interface FrozenCanvasResource {
    data class Pixels(val image: KoolCanvasArgbImage, val isStatic: Boolean) : FrozenCanvasResource
    data class Asset(val path: String, val encodedBytes: ByteArray) : FrozenCanvasResource
    data class Frame(val frame: KoolCanvasFrame) : FrozenCanvasResource
    data object Missing : FrozenCanvasResource
}

/** CPU data is immutable for the life of this lease; no legacy pixel array is stored here. */
class KoolCanvasResourceLease internal constructor(
    resources: Map<KoolCanvasTextureId, FrozenCanvasResource>,
    internal val fonts: KoolCanvasFontSnapshot,
) : AutoCloseable {
    private val references = AtomicInteger(1)
    private var ownedResources: Map<KoolCanvasTextureId, FrozenCanvasResource>? =
        Collections.unmodifiableMap(resources.toMap())
    val isReleased: Boolean get() = references.get() == 0
    val resourceCount: Int get() = synchronized(this) { ownedResources?.size ?: 0 }

    internal fun retain() {
        while (true) {
            val count = references.get()
            check(count > 0) { "Cannot retain expired frame resources" }
            if (references.compareAndSet(count, count + 1)) return
        }
    }

    internal fun resources(): Map<KoolCanvasTextureId, FrozenCanvasResource> = synchronized(this) {
        checkNotNull(ownedResources) { "Expired frame resources" }
    }

    override fun close() {
        val remaining = references.decrementAndGet()
        check(remaining >= 0) { "Unbalanced frame resource lease" }
        if (remaining == 0) {
            synchronized(this) { ownedResources = null }
            KoolCanvasFontRegistry.releaseSnapshot(fonts)
        }
    }
}

/**
 * Engine-thread recording store. Registration never constructs, uploads or releases a Texture2d.
 * Pixel ownership is detached at registration, once per real pixel revision, not once per frame.
 */
class KoolCanvasCpuTextureStore : KoolCanvasTextureStore, KoolCanvasFrameSnapshotRetainer,
    KoolCanvasTextureRevisionStore, KoolCanvasFrameTextureRevisionStore {
    private data class Source(val versionId: KoolCanvasTextureId, val resource: FrozenCanvasResource) {
        var frozenFrame: KoolCanvasFrame? = null
        var frozenVersionId: KoolCanvasTextureId? = null
    }
    private val sources = mutableMapOf<KoolCanvasTextureId, Source>()
    private val retainedFrames = mutableMapOf<KoolCanvasTextureId, Int>()
    private val ownerId = NEXT_OWNER.incrementAndGet()
    private var revision = 0
    private var frozenFrameSerial = 0L
    override val textureRevision: Int get() = revision
    override val frameTextureRevision: Int get() = revision

    override fun register(id: KoolCanvasTextureId, texture: Texture2d): Nothing =
        error("GPU textures cannot be registered by the engine recording thread")

    override fun resolve(texture: KoolCanvasTextureRef, filter: KoolCanvasTextureFilter): Nothing =
        error("GPU textures can only be resolved on the render thread")

    override fun registerArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray, alphaBleed: Boolean) {
        if (width <= 0 || height <= 0) return unregister(id)
        requireCompletePixels(width, height, argbPixels)
        val pixels = if (alphaBleed) KoolCanvasTextureRegistry.bleedTransparentRgb(width, height, argbPixels)
            else argbPixels.copyOf(width * height)
        put(id, FrozenCanvasResource.Pixels(KoolCanvasArgbImage(width, height, pixels), alphaBleed))
    }

    override fun registerOpaqueArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray) {
        if (width <= 0 || height <= 0) return unregister(id)
        requireCompletePixels(width, height, argbPixels)
        val pixels = IntArray(width * height) { argbPixels[it] or 0xff000000.toInt() }
        put(id, FrozenCanvasResource.Pixels(KoolCanvasArgbImage(width, height, pixels), false))
    }

    override fun registerPremultipliedArgb(id: KoolCanvasTextureId, width: Int, height: Int, argbPixels: IntArray) {
        if (width <= 0 || height <= 0) return unregister(id)
        requireCompletePixels(width, height, argbPixels)
        val pixels = KoolCanvasTextureRegistry.sanitizePremultipliedRgb(width, height, argbPixels)
        put(id, FrozenCanvasResource.Pixels(KoolCanvasArgbImage(width, height, pixels, true), false))
    }

    override fun registerAsset(id: KoolCanvasTextureId, assetPath: String) {
        val path = assetPath.removePrefix("assets/").replace('\\', '/')
        val bytes = sequenceOf(File(assetPath), File("assets", path))
            .firstOrNull { it.isFile }?.let { runCatching(it::readBytes).getOrNull() }
        registerAssetSnapshot(id, path, bytes)
    }

    override fun registerAssetSnapshot(id: KoolCanvasTextureId, assetPath: String, encodedBytes: ByteArray?) {
        val path = assetPath.removePrefix("assets/").replace('\\', '/')
        val previous = sources[id]?.resource as? FrozenCanvasResource.Asset
        if (encodedBytes == null) {
            if (sources[id]?.resource !== FrozenCanvasResource.Missing) put(id, FrozenCanvasResource.Missing)
        } else if (previous?.path != path || !previous.encodedBytes.contentEquals(encodedBytes)) {
            put(id, FrozenCanvasResource.Asset(path, encodedBytes.copyOf()))
        }
    }

    override fun registerFrame(id: KoolCanvasTextureId, frame: KoolCanvasFrame) {
        put(id, FrozenCanvasResource.Frame(frame.copy(commands = frame.commands.toList())))
    }

    override fun retainFrameSnapshot(id: KoolCanvasTextureId) {
        if (sources[id]?.resource is FrozenCanvasResource.Frame) retainedFrames[id] = (retainedFrames[id] ?: 0) + 1
    }

    override fun unregister(id: KoolCanvasTextureId) {
        retainedFrames[id]?.let { count ->
            if (count <= 1) retainedFrames.remove(id) else retainedFrames[id] = count - 1
            return
        }
        if (sources.remove(id) != null) revision++
    }

    override fun frame(id: KoolCanvasTextureId): KoolCanvasFrame? =
        (sources[id]?.resource as? FrozenCanvasResource.Frame)?.frame
    override fun argbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? = argbImageView(id)?.copyPixels()
    override fun argbImageView(id: KoolCanvasTextureId): KoolCanvasArgbImage? =
        (sources[id]?.resource as? FrozenCanvasResource.Pixels)?.image
    override fun staticArgbImage(id: KoolCanvasTextureId): KoolCanvasArgbImage? =
        (sources[id]?.resource as? FrozenCanvasResource.Pixels)?.takeIf { it.isStatic }?.image

    fun freezeFrame(
        frame: KoolCanvasFrame,
        sequence: Long,
        generation: Long,
        simulationTick: Int,
        viewportRevision: Long,
        camera: GameCameraSnapshot? = null,
    ): FrameEnvelope {
        val resources = linkedMapOf<KoolCanvasTextureId, FrozenCanvasResource>()
        val visiting = mutableSetOf<KoolCanvasTextureId>()
        val resolvedIds = mutableMapOf<KoolCanvasTextureId, KoolCanvasTextureId>()
        lateinit var freeze: (KoolCanvasFrame) -> KoolCanvasFrame
        fun texture(ref: KoolCanvasTextureRef): KoolCanvasTextureRef {
            val source = sources[ref.id]
            val id = source?.versionId ?: KoolCanvasTextureId("${ref.id.value}/cpu-$ownerId-missing")
            resolvedIds[id]?.let { return ref.copy(id = it) }
            if (id in visiting) {
                val cycleId = KoolCanvasTextureId("${id.value}/cycle")
                resources[cycleId] = FrozenCanvasResource.Missing
                return ref.copy(id = cycleId)
            }
            visiting += id
            val resource = source?.resource
            val resolvedId = if (resource is FrozenCanvasResource.Frame) {
                val frozen = freeze(resource.frame)
                if (source.frozenFrame != frozen) {
                    source.frozenFrame = frozen
                    source.frozenVersionId = KoolCanvasTextureId("${id.value}/frozen-${++frozenFrameSerial}")
                }
                checkNotNull(source.frozenVersionId).also { resources[it] = FrozenCanvasResource.Frame(frozen) }
            } else {
                resources[id] = resource ?: FrozenCanvasResource.Missing
                id
            }
            visiting -= id
            resolvedIds[id] = resolvedId
            return ref.copy(id = resolvedId)
        }
        fun paint(paint: KoolCanvasPaint): KoolCanvasPaint {
            val displacement = paint.textureEffect as? KoolCanvasTextureEffect.Displacement ?: return paint
            return paint.copy(textureEffect = displacement.copy(screenBase = texture(displacement.screenBase)))
        }
        freeze = { source ->
            source.copy(commands = Collections.unmodifiableList(source.commands.map { command ->
                when (command) {
                    is KoolCanvasCommand.DrawTexture -> command.copy(texture = texture(command.texture), paint = paint(command.paint))
                    is KoolCanvasCommand.DrawRect -> command.copy(paint = paint(command.paint))
                    is KoolCanvasCommand.DrawLine -> command.copy(paint = paint(command.paint))
                    is KoolCanvasCommand.DrawCircle -> command.copy(paint = paint(command.paint))
                    is KoolCanvasCommand.DrawText -> command.copy(paint = paint(command.paint))
                    is KoolCanvasCommand.Clear -> command
                }
            }))
        }
        val immutableFrame = freeze(frame)
        return FrameEnvelope(sequence, generation, simulationTick, viewportRevision, immutableFrame,
            KoolCanvasResourceLease(resources, KoolCanvasFontRegistry.snapshot()), camera)
    }

    private fun put(id: KoolCanvasTextureId, resource: FrozenCanvasResource) {
        revision++
        sources[id] = Source(KoolCanvasTextureId("${id.value}/cpu-$ownerId-version-$revision"), resource)
    }

    private fun requireCompletePixels(width: Int, height: Int, pixels: IntArray) {
        val count = width.toLong() * height
        require(count <= Int.MAX_VALUE / 4L && pixels.size >= count) { "Incomplete or oversized ARGB image" }
    }

    private companion object { val NEXT_OWNER = AtomicLong() }
}

/** Render-thread resource owners; fence retirement retains old versions until their last reader. */
internal object FrozenCanvasGpuResources {
    private val owners = mutableMapOf<KoolCanvasTextureId, Int>()
    fun install(lease: KoolCanvasResourceLease): AutoCloseable {
        val resources = lease.resources()
        resources.forEach { (id, resource) ->
            val oldOwners = owners[id] ?: 0
            owners[id] = oldOwners + 1
            if (oldOwners == 0) when (resource) {
                is FrozenCanvasResource.Pixels -> KoolCanvasTextureRegistry.installFrozenImage(id, resource.image, resource.isStatic)
                is FrozenCanvasResource.Asset -> {
                    // Decode only the version pinned in this packet. Never reopen a mod path
                    // after reload or when a delayed render finally consumes the frame.
                    val decoded = KoolGraphicsEngine.readPngImage(resource.encodedBytes)
                        ?: KoolGraphicsEngine.readPlatformImage(resource.encodedBytes, resource.path)
                    if (decoded != null) KoolCanvasTextureRegistry.registerArgb(
                        id, decoded.width, decoded.height, decoded.argbPixels)
                }
                is FrozenCanvasResource.Frame -> KoolCanvasTextureRegistry.registerFrame(id, resource.frame)
                FrozenCanvasResource.Missing -> Unit
            }
        }
        val closed = AtomicBoolean()
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) resources.keys.forEach { id ->
                val remaining = checkNotNull(owners[id]) - 1
                if (remaining == 0) {
                    owners.remove(id)
                    KoolCanvasTextureRegistry.unregister(id)
                } else owners[id] = remaining
            }
        }
    }
}
