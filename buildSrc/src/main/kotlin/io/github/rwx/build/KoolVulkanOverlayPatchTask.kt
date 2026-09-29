package io.github.rwx.build

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.InsnList
import org.objectweb.asm.tree.IntInsnNode
import org.objectweb.asm.tree.LdcInsnNode
import org.objectweb.asm.tree.LookupSwitchInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.VarInsnNode
import java.util.zip.ZipFile

abstract class KoolVulkanOverlayPatchTask : DefaultTask() {
    @get:Classpath
    abstract val koolDesktopJar: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun patch() {
        val classBytes = readClass(SWAPCHAIN_CLASS_ENTRY)
        val classNode = ClassNode()
        ClassReader(classBytes).accept(classNode, 0)

        val calls = classNode.methods.flatMap { method ->
            method.instructions.toArray()
                .filterIsInstance<MethodInsnNode>()
                .filter { instruction ->
                    instruction.owner == SWAPCHAIN_CREATE_INFO_OWNER &&
                            instruction.name == "compositeAlpha" &&
                            instruction.desc == "(I)L$SWAPCHAIN_CREATE_INFO_OWNER;"
                }
                .map { method to it }
        }
        check(calls.size == 1) {
            "Expected one Kool swapchain compositeAlpha call, found ${calls.size}"
        }

        val (method, call) = calls.single()
        val originalArgument = call.previous
        check(originalArgument.opcode == ICONST_1) {
            "Expected Kool 0.19.0 compositeAlpha argument to be ICONST_1"
        }
        method.instructions.insertBefore(
            originalArgument,
            InsnList().apply {
                add(VarInsnNode(ALOAD, SWAPCHAIN_SUPPORT_LOCAL_INDEX))
                add(
                    MethodInsnNode(
                        INVOKEVIRTUAL,
                        SWAPCHAIN_SUPPORT_OWNER,
                        "getCapabilities",
                        "()L$SURFACE_CAPABILITIES_OWNER;",
                        false,
                    ),
                )
                add(
                    MethodInsnNode(
                        INVOKEVIRTUAL,
                        SURFACE_CAPABILITIES_OWNER,
                        "supportedCompositeAlpha",
                        "()I",
                        false,
                    ),
                )
                add(
                    MethodInsnNode(
                        INVOKESTATIC,
                        COMPOSITE_ALPHA_SELECTOR_OWNER,
                        "select",
                        "(I)I",
                        false,
                    ),
                )
            },
        )
        method.instructions.remove(originalArgument)

        patchSwapchainAcquireTimeout(classNode)

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classNode.accept(writer)
        writeClass(SWAPCHAIN_CLASS_ENTRY, writer.toByteArray())

        patchDrawPipeline()
    }

    /**
     * Bounds the swapchain image acquisition.
     *
     * Kool waits for the next swapchain image with an infinite timeout
     * (`vkAcquireNextImageKHR(..., -1, ...)`) and its Swing loop drives the game loop from the same
     * thread, so an occluded window, a sleeping display or a lost surface stops the whole game
     * instead of dropping frames. The acquire timeout becomes finite (see [ACQUIRE_TIMEOUT_NANOS])
     * and `VK_TIMEOUT` is handled like `VK_ERROR_OUT_OF_DATE_KHR` (return `false`), which makes
     * Kool skip the frame and recreate the swapchain until drawables come back.
     *
     * The fence wait before it stays infinite on purpose: it waits for our own previous submission,
     * and timing that out would let the next frame reuse a still in-flight buffer.
     */
    private fun patchSwapchainAcquireTimeout(classNode: ClassNode) {
        val method = classNode.methods.singleOrNull { it.name == "acquireNextImage" && it.desc == "()Z" }
            ?: error("Kool Swapchain.acquireNextImage was not found")
        val instructions = method.instructions.toArray()

        val timeouts = instructions.filterIsInstance<LdcInsnNode>().filter { it.cst == -1L }
        check(timeouts.size == 2) {
            "Expected two infinite swapchain timeouts, found ${timeouts.size}"
        }
        // First is the fence wait, second the image acquisition; only the acquisition is bounded.
        timeouts.last().cst = ACQUIRE_TIMEOUT_NANOS

        val switch = instructions.filterIsInstance<LookupSwitchInsnNode>()
            .singleOrNull { VK_ERROR_OUT_OF_DATE_KHR in it.keys }
            ?: error("Kool Swapchain.acquireNextImage switch was not found")
        check(VK_TIMEOUT !in switch.keys) {
            "Kool already handles the swapchain acquire timeout"
        }
        val skipFrameLabel = switch.labels[switch.keys.indexOf(VK_ERROR_OUT_OF_DATE_KHR)]
        val keys = switch.keys.toMutableList()
        val labels = switch.labels.toMutableList()
        val insertAt = keys.indexOfFirst { it > VK_TIMEOUT }.let { if (it < 0) keys.size else it }
        keys.add(insertAt, VK_TIMEOUT)
        labels.add(insertAt, skipFrameLabel)
        switch.keys = keys
        switch.labels = labels
    }

    private fun patchDrawPipeline() {
        val classNode = ClassNode()
        ClassReader(readClass(DRAW_PIPELINE_CLASS_ENTRY)).accept(classNode, 0)
        val blendInfo = classNode.methods.singleOrNull { method ->
            method.name == "blendInfo" &&
                method.desc ==
                "(Lorg/lwjgl/system/MemoryStack;Lde/fabmax/kool/pipeline/backend/vk/PassEncoderState;)" +
                "Lorg/lwjgl/vulkan/VkPipelineColorBlendStateCreateInfo;"
        } ?: error("Kool DrawPipelineVk blendInfo method was not found")

        val alphaFactorCalls = blendInfo.instructions.toArray()
            .filterIsInstance<MethodInsnNode>()
            .filter { instruction ->
                instruction.owner == COLOR_BLEND_ATTACHMENT_OWNER &&
                    instruction.name == "dstAlphaBlendFactor" &&
                    instruction.desc == "(I)L$COLOR_BLEND_ATTACHMENT_OWNER;"
            }
        check(alphaFactorCalls.size == 3) {
            "Expected three Kool destination alpha blend factors, found ${alphaFactorCalls.size}"
        }

        // Kool 0.19.0 replaces destination alpha for multiply/premultiplied-alpha draws.
        // Transparent texture texels then punch holes through already-rendered UI surfaces.
        alphaFactorCalls.drop(1).forEach { call ->
            val originalArgument = call.previous
            check(originalArgument.opcode == ICONST_0) {
                "Expected Kool destination alpha blend factor argument to be ICONST_0"
            }
            blendInfo.instructions.insertBefore(
                originalArgument,
                IntInsnNode(BIPUSH, VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA),
            )
            blendInfo.instructions.remove(originalArgument)
        }

        val writer = ClassWriter(ClassWriter.COMPUTE_MAXS)
        classNode.accept(writer)
        writeClass(DRAW_PIPELINE_CLASS_ENTRY, writer.toByteArray())
    }

    private fun readClass(entryName: String): ByteArray = ZipFile(koolDesktopJar.get().asFile).use { jar ->
        val entry = requireNotNull(jar.getEntry(entryName)) {
            "Kool desktop JAR does not contain $entryName"
        }
        jar.getInputStream(entry).use { it.readBytes() }
    }

    private fun writeClass(entryName: String, bytes: ByteArray) {
        val outputFile = outputDirectory.file(entryName).get().asFile
        outputFile.parentFile.mkdirs()
        outputFile.writeBytes(bytes)
    }

    companion object {
        private const val SWAPCHAIN_CLASS_ENTRY =
            "de/fabmax/kool/pipeline/backend/vk/Swapchain.class"
        private const val SWAPCHAIN_CREATE_INFO_OWNER =
            "org/lwjgl/vulkan/VkSwapchainCreateInfoKHR"
        private const val SWAPCHAIN_SUPPORT_OWNER =
            "de/fabmax/kool/pipeline/backend/vk/PhysicalDevice\$SwapChainSupportDetails"
        private const val SURFACE_CAPABILITIES_OWNER =
            "org/lwjgl/vulkan/VkSurfaceCapabilitiesKHR"
        private const val COMPOSITE_ALPHA_SELECTOR_OWNER =
            "io/github/rwx/VulkanCompositeAlpha"
        private const val SWAPCHAIN_SUPPORT_LOCAL_INDEX = 3
        private const val DRAW_PIPELINE_CLASS_ENTRY =
            "de/fabmax/kool/pipeline/backend/vk/DrawPipelineVk.class"
        private const val COLOR_BLEND_ATTACHMENT_OWNER =
            "org/lwjgl/vulkan/VkPipelineColorBlendAttachmentState"
        private const val VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA = 7

        /** 100ms: long enough not to fire on a healthy compositor, short enough to keep the loop alive. */
        private const val ACQUIRE_TIMEOUT_NANOS = 100_000_000L
        private const val VK_TIMEOUT = 2
        private const val VK_ERROR_OUT_OF_DATE_KHR = -1000001004
    }
}
