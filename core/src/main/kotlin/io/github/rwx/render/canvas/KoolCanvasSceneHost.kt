package io.github.rwx.render.canvas

import de.fabmax.kool.scene.OnRenderScene
import de.fabmax.kool.scene.Scene
import io.github.rwx.logger

/**
 * Frame statistics for the canvas replay itself, enabled with `RWX_CANVAS_PERF=1`.
 *
 * [KoolCanvasFrameRenderer.render] turns the game's command stream into meshes and instance buffers
 * and runs inside Kool's scene render, i.e. after the game session returned from `updateFrame`.
 * Neither `RWX_PERF_LOG` (which measures the game loop) nor the Slick frame log can attribute time
 * to it, so a frame that spends all its budget here still looks like "0.2ms of work".
 */
internal object KoolCanvasRenderProbe {
    private var total = 0L
    private var peak = 0L
    private var count = 0
    private var windowStart = System.nanoTime()

    fun record(nanos: Long) {
        if (System.getenv("RWX_CANVAS_PERF") != "1") return
        total += nanos
        peak = maxOf(peak, nanos)
        count++
        val now = System.nanoTime()
        if (now - windowStart >= WINDOW_NANOS) {
            if (count > 0) {
                logger.info("RWXPerf") {
                    "canvas frames=$count replay[avg=%.2fms peak=%.2fms]".format(
                        total.toDouble() / count / 1e6,
                        peak / 1e6,
                    )
                }
            }
            total = 0
            peak = 0
            count = 0
            windowStart = now
        }
    }

    private const val WINDOW_NANOS = 2_000_000_000L
}

class KoolCanvasSceneHost(
    private val frameRenderer: KoolCanvasFrameRenderer = KoolCanvasFrameRenderer(),
    private val sceneName: String = DEFAULT_SCENE_NAME,
) : KoolCanvasRenderer {
    private var activeScene: Scene? = null
    private val mailbox = LatestFrameMailbox()
    private var currentEnvelope: FrameEnvelope? = null
    private var installedResources: AutoCloseable? = null
    private var renderedEnvelope: FrameEnvelope? = null
    private var retirementSink: ((() -> Unit) -> Unit)? = null
    private var presentationTracker: CanvasFramePresentationTracker? = null
    private val presentationOwner = Any()
    @Volatile
    private var useEnvelope = false

    @Volatile
    private var latestFrame: KoolCanvasFrame = EmptyFrame

    val scene: Scene?
        get() = activeScene

    fun createScene(): Scene = Scene(sceneName).also(::configure)

    override fun render(frame: KoolCanvasFrame) {
        latestFrame = frame.copy(commands = frame.commands.toList())
        useEnvelope = false
    }

    /** Takes ownership of [envelope]; unused pending frames are released immediately. */
    fun submit(envelope: FrameEnvelope) {
        mailbox.publish(envelope)
        useEnvelope = true
    }

    /** Set on the render thread. Vulkan supplies a callback tied to successful fence completion. */
    fun setGpuRetirementSink(sink: ((() -> Unit) -> Unit)?) {
        retirementSink = sink
        KoolCanvasGpuRetirement.install(sink)
    }

    fun setPresentationTracker(tracker: CanvasFramePresentationTracker?) { presentationTracker = tracker }

    fun currentFrame(): KoolCanvasFrame = currentEnvelope?.frame ?: latestFrame

    private fun retireCurrent() {
        val envelope = currentEnvelope ?: return
        val installed = installedResources
        currentEnvelope = null
        installedResources = null
        val release = { installed?.close(); envelope.close() }
        retirementSink?.invoke(release) ?: release()
    }

    private fun configure(scene: Scene) {
        activeScene = scene
        scene.onRelease {
            CanvasFramePresentation.clear(presentationOwner)
            presentationTracker?.clear()
            mailbox.close()
            retireCurrent()
            activeScene = null
            renderedEnvelope = null
        }
        scene.onRenderScene += OnRenderScene {
            var replayed = false
            if (!useEnvelope) {
                mailbox.clear()
                retireCurrent()
            }
            mailbox.poll()?.let { next ->
                // Install first: shared versions retain an owner while the previous GPU lease retires.
                val installed = FrozenCanvasGpuResources.install(next.resourceLease)
                retireCurrent()
                currentEnvelope = next
                installedResources = installed
            }
            val envelope = currentEnvelope
            if (envelope != null) {
                CanvasFrameMetrics.chosen(envelope.sequence, envelope.generation)
                if (renderedEnvelope !== envelope) {
                    val startedAt = System.nanoTime()
                    KoolCanvasFontRegistry.withSnapshot(envelope.resourceLease.fonts) {
                        frameRenderer.render(scene, envelope.frame.withSurfaceClear())
                    }
                    renderedEnvelope = envelope
                    replayed = true
                    KoolCanvasRenderProbe.record(System.nanoTime() - startedAt)
                }
                CanvasFramePresentation.chosen(presentationOwner, presentationTracker, envelope.sequence, envelope.generation, envelope.camera)
            } else {
                CanvasFramePresentation.clear(presentationOwner)
                val startedAt = System.nanoTime()
                frameRenderer.render(scene, latestFrame)
                replayed = true
                KoolCanvasRenderProbe.record(System.nanoTime() - startedAt)
            }
            // Repeated presentation still drains textures already proven GPU-idle by the sink.
            if (!replayed) {
                if (envelope != null) KoolCanvasFontRegistry.withSnapshot(envelope.resourceLease.fonts) {
                    frameRenderer.refreshPerformanceHud(envelope.frame.viewport)
                }
                KoolCanvasTextureRegistry.releaseRetiredTextures()
            }
        }
    }

    companion object {
        const val DEFAULT_SCENE_NAME: String = "kool-canvas"
        val EmptyFrame: KoolCanvasFrame = KoolCanvasFrame(KoolCanvasViewport(0, 0), emptyList())
    }
}

private fun KoolCanvasFrame.withSurfaceClear(): KoolCanvasFrame =
    if (commands.any { it is KoolCanvasCommand.Clear && it.renderTarget == null }) this
    else copy(commands = listOf(KoolCanvasCommand.Clear(KoolCanvasColor(0xff000000.toInt()), KoolCanvasBlendMode.Source)) + commands)
