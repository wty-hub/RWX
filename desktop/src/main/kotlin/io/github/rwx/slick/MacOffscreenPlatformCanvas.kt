package io.github.rwx.slick

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.CGL
import org.lwjgl.opengl.EXTFramebufferObject
import org.lwjgl.opengl.GL
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL14
import org.lwjgl.opengl.GLCapabilities
import org.lwjgl.opengl.awt.GLData
import org.lwjgl.opengl.awt.PlatformGLCanvas
import java.awt.AWTException
import java.awt.Canvas
import java.awt.Dimension
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt

/**
 * A drawable-free CGL context whose render target is an FBO. The Kool canvas presents the frame.
 *
 * lwjgl3-awt's macOS canvas creates a layer-backed NSOpenGLView. CoreAnimation can ask that view
 * to attach its context on the AWT event queue, aborting the process because NSOpenGLContext.setView
 * must run on Cocoa's main thread. This backend never creates an NSOpenGLView or JAWT surface.
 */
internal class MacOffscreenPlatformCanvas : PlatformGLCanvas {
    private val lock = ReentrantLock()
    private var canvas: Canvas? = null
    private var nativeContext = 0L
    private var capabilities: GLCapabilities? = null
    private var framebuffer = 0
    private var colorTexture = 0
    private var depthBuffer = 0
    private var stencilBuffer = 0
    private var bufferWidth = 0
    private var bufferHeight = 0

    override fun create(canvas: Canvas, data: GLData, effective: GLData): Long = lock.withLock {
        check(nativeContext == 0L) { "Offscreen Slick context already exists" }
        val attributes = BufferUtils.createIntBuffer(12).put(
            intArrayOf(
                CGL.kCGLPFAAccelerated,
                CGL.kCGLPFAColorSize, 24,
                CGL.kCGLPFAAlphaSize, data.alphaSize,
                CGL.kCGLPFADepthSize, data.depthSize,
                CGL.kCGLPFAStencilSize, data.stencilSize,
                CGL.kCGLPFAOpenGLProfile, NS_OPENGL_PROFILE_LEGACY,
                0,
            ),
        )
        attributes.flip()
        val formats = BufferUtils.createPointerBuffer(1)
        val screenCount = BufferUtils.createIntBuffer(1)
        val chooseResult = CGL.CGLChoosePixelFormat(attributes, formats, screenCount)
        val format = formats.get(0)
        if (chooseResult != 0 || format == 0L) {
            if (format != 0L) CGL.CGLDestroyPixelFormat(format)
            throw AWTException("CGLChoosePixelFormat for offscreen Slick failed: $chooseResult")
        }
        try {
            val contexts = BufferUtils.createPointerBuffer(1)
            val createResult = CGL.CGLCreateContext(format, 0L, contexts)
            nativeContext = contexts.get(0)
            if (createResult != 0 || nativeContext == 0L) {
                throw AWTException("CGLCreateContext for offscreen Slick failed: $createResult")
            }
            this.canvas = canvas
            effective.doubleBuffer = false
            effective.alphaSize = data.alphaSize
            effective.depthSize = data.depthSize
            effective.stencilSize = data.stencilSize
            nativeContext
        } catch (failure: Throwable) {
            disposeNative()
            throw failure
        } finally {
            CGL.CGLDestroyPixelFormat(format)
        }
    }

    override fun makeCurrent(context: Long): Boolean {
        if (context == 0L) return CGL.CGLSetCurrentContext(0L) == 0
        check(context == nativeContext) { "Unexpected offscreen Slick context" }
        val result = CGL.CGLSetCurrentContext(context)
        if (result != 0) throw IllegalStateException("CGLSetCurrentContext failed: $result")
        val knownCapabilities = capabilities
        if (knownCapabilities == null) {
            capabilities = GL.createCapabilities().also {
                check(it.GL_EXT_framebuffer_object) { "Offscreen Slick requires GL_EXT_framebuffer_object" }
            }
        } else {
            GL.setCapabilities(knownCapabilities)
        }
        canvas?.deviceDimensions()?.let { (width, height) ->
            if (framebuffer == 0 || width != bufferWidth || height != bufferHeight) {
                createFramebuffer(width, height)
            }
        }
        bindFramebuffer()
        return true
    }

    /** Rebind after Slick temporarily renders into another FBO. Must run in the Slick GL context. */
    fun bindFramebuffer() {
        check(framebuffer != 0) { "Offscreen Slick framebuffer is unavailable" }
        EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, framebuffer)
        GL11.glDrawBuffer(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT)
        GL11.glReadBuffer(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT)
    }

    fun framebufferSize(): Dimension? =
        if (framebuffer != 0) Dimension(bufferWidth, bufferHeight) else null

    override fun isCurrent(context: Long): Boolean = CGL.CGLGetCurrentContext() == context

    override fun swapBuffers(): Boolean {
        GL11.glFlush()
        return true
    }

    override fun delayBeforeSwapNV(seconds: Float): Boolean = false

    override fun lock() = lock.lock()

    override fun unlock() = lock.unlock()

    override fun deleteContext(context: Long): Boolean {
        if (context != nativeContext || context == 0L) return false
        dispose()
        return true
    }

    override fun dispose() = lock.withLock { disposeNative() }

    private fun disposeNative() {
        val context = nativeContext
        if (context == 0L) return
        val previousContext = CGL.CGLGetCurrentContext()
        val previousCapabilities = runCatching { GL.getCapabilities() }.getOrNull()
        if (capabilities != null && CGL.CGLSetCurrentContext(context) == 0) {
            GL.setCapabilities(capabilities)
            deleteFramebuffer()
        }
        if (previousContext != context) {
            CGL.CGLSetCurrentContext(previousContext)
        } else {
            CGL.CGLSetCurrentContext(0L)
        }
        GL.setCapabilities(if (previousContext == context) null else previousCapabilities)
        CGL.CGLDestroyContext(context)
        nativeContext = 0L
        capabilities = null
        canvas = null
    }

    private fun createFramebuffer(width: Int, height: Int) {
        val previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        val previousRenderbuffer = GL11.glGetInteger(EXTFramebufferObject.GL_RENDERBUFFER_BINDING_EXT)
        val newTexture = GL11.glGenTextures()
        val newFramebuffer = EXTFramebufferObject.glGenFramebuffersEXT()
        val newDepth = EXTFramebufferObject.glGenRenderbuffersEXT()
        val newStencil = EXTFramebufferObject.glGenRenderbuffersEXT()
        try {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, newTexture)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL14.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL14.GL_CLAMP_TO_EDGE)
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L,
            )
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture)

            EXTFramebufferObject.glBindFramebufferEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT, newFramebuffer)
            EXTFramebufferObject.glFramebufferTexture2DEXT(
                EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT,
                GL11.GL_TEXTURE_2D, newTexture, 0,
            )
            EXTFramebufferObject.glBindRenderbufferEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, newDepth)
            EXTFramebufferObject.glRenderbufferStorageEXT(
                EXTFramebufferObject.GL_RENDERBUFFER_EXT, GL14.GL_DEPTH_COMPONENT24, width, height,
            )
            EXTFramebufferObject.glFramebufferRenderbufferEXT(
                EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                EXTFramebufferObject.GL_DEPTH_ATTACHMENT_EXT,
                EXTFramebufferObject.GL_RENDERBUFFER_EXT, newDepth,
            )
            EXTFramebufferObject.glBindRenderbufferEXT(EXTFramebufferObject.GL_RENDERBUFFER_EXT, newStencil)
            EXTFramebufferObject.glRenderbufferStorageEXT(
                EXTFramebufferObject.GL_RENDERBUFFER_EXT, EXTFramebufferObject.GL_STENCIL_INDEX8_EXT, width, height,
            )
            EXTFramebufferObject.glFramebufferRenderbufferEXT(
                EXTFramebufferObject.GL_FRAMEBUFFER_EXT,
                EXTFramebufferObject.GL_STENCIL_ATTACHMENT_EXT,
                EXTFramebufferObject.GL_RENDERBUFFER_EXT, newStencil,
            )
            GL11.glDrawBuffer(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT)
            GL11.glReadBuffer(EXTFramebufferObject.GL_COLOR_ATTACHMENT0_EXT)
            val status = EXTFramebufferObject.glCheckFramebufferStatusEXT(EXTFramebufferObject.GL_FRAMEBUFFER_EXT)
            check(status == EXTFramebufferObject.GL_FRAMEBUFFER_COMPLETE_EXT) {
                "Offscreen Slick framebuffer incomplete: 0x${status.toString(16)}"
            }
        } catch (failure: Throwable) {
            EXTFramebufferObject.glDeleteRenderbuffersEXT(newStencil)
            EXTFramebufferObject.glDeleteRenderbuffersEXT(newDepth)
            EXTFramebufferObject.glDeleteFramebuffersEXT(newFramebuffer)
            GL11.glDeleteTextures(newTexture)
            throw failure
        } finally {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture)
            EXTFramebufferObject.glBindRenderbufferEXT(
                EXTFramebufferObject.GL_RENDERBUFFER_EXT,
                previousRenderbuffer,
            )
        }
        deleteFramebuffer()
        framebuffer = newFramebuffer
        colorTexture = newTexture
        depthBuffer = newDepth
        stencilBuffer = newStencil
        bufferWidth = width
        bufferHeight = height
    }

    private fun deleteFramebuffer() {
        if (framebuffer != 0) EXTFramebufferObject.glDeleteFramebuffersEXT(framebuffer)
        if (colorTexture != 0) GL11.glDeleteTextures(colorTexture)
        if (depthBuffer != 0) EXTFramebufferObject.glDeleteRenderbuffersEXT(depthBuffer)
        if (stencilBuffer != 0) EXTFramebufferObject.glDeleteRenderbuffersEXT(stencilBuffer)
        framebuffer = 0
        colorTexture = 0
        depthBuffer = 0
        stencilBuffer = 0
        bufferWidth = 0
        bufferHeight = 0
    }

    private fun Canvas.deviceDimensions(): Pair<Int, Int> {
        val scale = graphicsConfiguration?.defaultTransform
        val deviceWidth = (width.coerceAtLeast(1) * (scale?.scaleX ?: 1.0)).roundToInt().coerceAtLeast(1)
        val deviceHeight = (height.coerceAtLeast(1) * (scale?.scaleY ?: 1.0)).roundToInt().coerceAtLeast(1)
        return deviceWidth to deviceHeight
    }

    private companion object {
        const val NS_OPENGL_PROFILE_LEGACY = 0x1000
    }
}
