package io.github.rwx.kool.vulkan

import de.fabmax.kool.pipeline.backend.vk.MemoryManager
import de.fabmax.kool.pipeline.backend.vk.VkBuffer
import de.fabmax.kool.pipeline.backend.vk.VkImage
import java.util.WeakHashMap

/** Implemented by the generated MemoryManager overlay, preserving Kool's allocation accounting. */
interface VulkanNativeMemoryAccess {
    fun rwxFreeBufferImmediate(buffer: VkBuffer, deferTicks: Int)
    fun rwxFreeImageImmediate(image: VkImage, deferTicks: Int)
}

object VulkanNativeMemoryRetirement {
    private val releasedAllocators = WeakHashMap<MemoryManager, Boolean>()

    @JvmStatic
    fun allocatorReleased(manager: MemoryManager) { releasedAllocators[manager] = true }

    @JvmStatic
    fun freeBuffer(manager: MemoryManager, buffer: VkBuffer, deferTicks: Int) {
        if (manager in releasedAllocators) return
        val access = (manager as Any) as VulkanNativeMemoryAccess
        if (deferTicks <= 0) access.rwxFreeBufferImmediate(buffer, 0)
        else VulkanFrameLifecycle.retireNative(manager.backend) { access.rwxFreeBufferImmediate(buffer, 0) }
    }

    @JvmStatic
    fun freeImage(manager: MemoryManager, image: VkImage, deferTicks: Int) {
        if (manager in releasedAllocators) return
        val access = (manager as Any) as VulkanNativeMemoryAccess
        if (deferTicks <= 0) access.rwxFreeImageImmediate(image, 0)
        else VulkanFrameLifecycle.retireNative(manager.backend) { access.rwxFreeImageImmediate(image, 0) }
    }
}
