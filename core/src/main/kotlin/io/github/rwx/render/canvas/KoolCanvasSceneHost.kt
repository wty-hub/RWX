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

    @Volatile
    private var latestFrame: KoolCanvasFrame = EmptyFrame

    val scene: Scene?
        get() = activeScene

    fun createScene(): Scene = Scene(sceneName).also(::configure)

    override fun render(frame: KoolCanvasFrame) {
        latestFrame = frame.copy(commands = frame.commands.toList())
    }

    fun currentFrame(): KoolCanvasFrame = latestFrame

    private fun configure(scene: Scene) {
        activeScene = scene
        scene.onRenderScene += OnRenderScene {
            val startedAt = System.nanoTime()
            frameRenderer.render(scene, latestFrame)
            KoolCanvasRenderProbe.record(System.nanoTime() - startedAt)
        }
    }

    companion object {
        const val DEFAULT_SCENE_NAME: String = "kool-canvas"
        val EmptyFrame: KoolCanvasFrame = KoolCanvasFrame(KoolCanvasViewport(0, 0), emptyList())
    }
}
