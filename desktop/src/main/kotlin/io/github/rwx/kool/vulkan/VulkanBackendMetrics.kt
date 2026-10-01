package io.github.rwx.kool.vulkan

import de.fabmax.kool.pipeline.backend.stats.BackendStats
import java.io.File
import java.util.Locale

/** Native-stage counters supplement engine / Canvas timing; emitted only for successful submissions. */
internal object VulkanBackendMetrics {
    private val target = System.getenv("RWX_VK_METRICS")?.takeIf { it.isNotBlank() }
    val enabled: Boolean get() = target != null
    private var start = System.nanoTime()
    private var frames = 0
    private var bufferBytes = 0L
    private var textureBytes = 0L
    private var uploadNanos = 0L
    private var gpuNanos = 0L
    private var drawCommands = 0L
    private var previousAllocations = 0L
    private var fenceWaitNanos = 0L
    private var acquireNanos = 0L
    private var presentNanos = 0L
    private var submitNanos = 0L

    inline fun measureSubmit(call: () -> Int): Int {
        if (!enabled) return call()
        val start = System.nanoTime()
        return try { call() } finally { submitNanos += System.nanoTime() - start }
    }

    inline fun measureFenceWait(call: () -> Int): Int {
        if (!enabled) return call()
        val start = System.nanoTime()
        return try { call() } finally { fenceWaitNanos += System.nanoTime() - start }
    }

    inline fun measureAcquire(call: () -> Int): Int {
        if (!enabled) return call()
        val start = System.nanoTime()
        return try { call() } finally { acquireNanos += System.nanoTime() - start }
    }

    inline fun measurePresent(call: () -> Int): Int {
        if (!enabled) return call()
        val start = System.nanoTime()
        return try { call() } finally { presentNanos += System.nanoTime() - start }
    }

    fun submitted(state: VulkanUploadState) {
        val output = target ?: return
        frames++
        bufferBytes += state.bufferUploadBytes
        textureBytes += state.textureUploadBytes
        uploadNanos += state.uploadNanos
        gpuNanos += state.backend.frameGpuTime.inWholeNanoseconds
        drawCommands += BackendStats.numDrawCommands
        val now = System.nanoTime()
        val seconds = (now - start) / 1e9
        if (seconds < 5) return
        val line = String.format(Locale.ROOT,
            "{\"seconds\":%.3f,\"submittedFrames\":%d,\"uploadCpuAvgMs\":%.4f,\"gpuAvgMs\":%.4f,\"drawCommandsAvg\":%.2f,\"bufferUploadBytes\":%d,\"textureUploadBytes\":%d,\"stagingAllocations\":%d,\"stagingResidentBytes\":%d,\"retirementCallbacks\":%d,\"pipelineCount\":%d,\"gpuBufferBytes\":%d,\"gpuTextureBytes\":%d,\"framebufferWidth\":%d,\"framebufferHeight\":%d,\"sampleCount\":%d,\"presentMode\":%d,\"targetFps\":%d,\"fenceWaitAvgMs\":%.4f,\"acquireAvgMs\":%.4f,\"queuePresentAvgMs\":%.4f,\"queueSubmitAvgMs\":%.4f}",
            seconds, frames, uploadNanos.toDouble() / frames / 1e6, gpuNanos.toDouble() / frames / 1e6,
            drawCommands.toDouble() / frames, bufferBytes, textureBytes, state.allocationCount - previousAllocations,
            state.residentStagingBytes, state.retirement.pendingCount, BackendStats.pipelines.size,
            BackendStats.totalBufferSize, BackendStats.totalTextureSize,
            state.backend.swapchain.width, state.backend.swapchain.height,
            state.backend.swapchain.numSamples,
            VulkanFrameLifecycle.presentMode, state.backend.ctx.maxFrameRate,
            fenceWaitNanos.toDouble() / frames / 1e6, acquireNanos.toDouble() / frames / 1e6,
            presentNanos.toDouble() / frames / 1e6, submitNanos.toDouble() / frames / 1e6)
        if (output == "1") println("RWXVulkanMetrics $line") else File(output).appendText(line + "\n")
        previousAllocations = state.allocationCount
        frames = 0; bufferBytes = 0; textureBytes = 0; uploadNanos = 0; gpuNanos = 0; drawCommands = 0
        fenceWaitNanos = 0; acquireNanos = 0; presentNanos = 0; submitNanos = 0; start = now
    }
}
