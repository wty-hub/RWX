package io.github.rwx.render.canvas

import io.github.rwx.session.GameCameraSnapshot

/**
 * A session's immutable camera acknowledged by its native presentation path. Selecting a picture
 * does not publish it: acquire / present may reject that picture, including during a resize.
 * This holds no frame envelope, texture lease, engine object or native resource.
 */
class CanvasFramePresentationTracker : AutoCloseable {
    internal data class Candidate(
        val tracker: CanvasFramePresentationTracker,
        val epoch: Long,
        val sequence: Long,
        val generation: Long,
        val camera: GameCameraSnapshot?,
    )

    private var epoch = 0L
    private var closed = false
    private var accepted: Candidate? = null

    @Synchronized fun cameraSnapshot(): GameCameraSnapshot? = accepted?.camera

    @Synchronized internal fun candidate(sequence: Long, generation: Long, camera: GameCameraSnapshot?): Candidate? {
        if (closed) return null
        require(camera == null || camera.generation == generation) { "Picture and camera generations differ" }
        return Candidate(this, epoch, sequence, generation, camera)
    }

    @Synchronized internal fun acknowledge(candidate: Candidate): Boolean {
        if (closed || candidate.tracker !== this || candidate.epoch != epoch) return false
        val previous = accepted
        if (previous != null && (candidate.generation < previous.generation ||
                    (candidate.generation == previous.generation && candidate.sequence < previous.sequence))) return false
        accepted = candidate
        return true
    }

    /** A released scene can no longer acknowledge a candidate captured before its release. */
    @Synchronized fun clear() { epoch++; accepted = null }

    @Synchronized override fun close() { closed = true; clear() }
}

/** A render owner stages at most one camera; only an actual accepted native present commits it. */
internal class CanvasFramePresentationBridge {
    private data class Selection(val owner: Any, val candidate: CanvasFramePresentationTracker.Candidate)
    private var selected: Selection? = null

    /** Also discards a candidate left behind by an unsuccessful swapchain acquire. */
    @Synchronized fun beginFrame() { selected = null }

    @Synchronized fun chosen(owner: Any, tracker: CanvasFramePresentationTracker?, sequence: Long,
        generation: Long, camera: GameCameraSnapshot?) {
        selected = tracker?.candidate(sequence, generation, camera)?.let { Selection(owner, it) }
    }

    @Synchronized fun presented(accepted: Boolean): Boolean {
        val selection = selected
        selected = null
        return accepted && selection != null && selection.candidate.tracker.acknowledge(selection.candidate)
    }

    @Synchronized fun clear(owner: Any) { if (selected?.owner === owner) selected = null }
}

/** Desktop Vulkan and visible OpenGL swaps use this bridge; headless never acknowledges a display. */
object CanvasFramePresentation {
    private val bridge = CanvasFramePresentationBridge()
    fun beginFrame() = bridge.beginFrame()
    fun chosen(owner: Any, tracker: CanvasFramePresentationTracker?, sequence: Long,
        generation: Long, camera: GameCameraSnapshot?) = bridge.chosen(owner, tracker, sequence, generation, camera)
    fun presented(accepted: Boolean) = bridge.presented(accepted)
    fun clear(owner: Any) = bridge.clear(owner)
}
