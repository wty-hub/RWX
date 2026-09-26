package io.github.rwx

import org.slf4j.LoggerFactory
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.lang.reflect.Method

/**
 * Tells the X input method where the caret is.
 *
 * OpenJDK never sets `XNSpotLocation`. fcitx5 then falls back to the bottom-left of the focus
 * window; IBus does the same. libX11 1.8.2 and later honors the spot for on-the-spot XICs too.
 * The XIC pointer is the first field of the native struct stored in
 * `sun.awt.X11InputMethodBase.pData`.
 */
internal object LinuxImeCandidateSpot {
    private val linux = System.getProperty("os.name").contains("linux", ignoreCase = true)
    private val logger = LoggerFactory.getLogger("Desktop")
    private var failed = false
    private var bindings: X11Bindings? = null
    private val awtLock: Method? by lazy { toolkitMethod("awtLock") }
    private val awtUnlock: Method? by lazy { toolkitMethod("awtUnlock") }

    fun move(x: Int, y: Int) {
        if (!linux || failed) return
        try {
            val inputContext = currentInputContext() ?: return
            val xic = xicPointer(inputContext) ?: return
            val x11 = bindings ?: X11Bindings.open().also { bindings = it }
            val spotX = x.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            val spotY = y.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            lockAwt()
            try {
                x11.setSpot(xic, spotX, spotY)
            } finally {
                unlockAwt()
            }
        } catch (error: Throwable) {
            failed = true
            logger.warn("Could not move the input-method candidate window to {},{}", x, y, error)
        }
    }

    private fun currentInputContext(): Any? {
        val focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner ?: return null
        return focus.inputContext
    }

    private fun xicPointer(inputContext: Any): MemorySegment? {
        val method = fieldValue(inputContext, "inputMethod") ?: return null
        val pData = fieldValue(method, "pData") as? Long ?: return null
        if (pData == 0L) return null
        val data = MemorySegment.ofAddress(pData).reinterpret(ValueLayout.ADDRESS.byteSize())
        val xic = data.get(ValueLayout.ADDRESS, 0)
        if (xic.address() == 0L) return null
        return xic
    }

    private fun fieldValue(instance: Any, name: String): Any? {
        var type: Class<*>? = instance.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { it.name == name }
            if (field != null) {
                field.isAccessible = true
                return field.get(instance)
            }
            type = type.superclass
        }
        return null
    }

    private fun lockAwt() {
        awtLock?.invoke(null)
    }

    private fun unlockAwt() {
        awtUnlock?.invoke(null)
    }

    private fun toolkitMethod(name: String): Method? =
        runCatching { Class.forName("sun.awt.SunToolkit").getMethod(name) }.getOrNull()

    private class X11Bindings private constructor(
        private val createNestedList: java.lang.invoke.MethodHandle,
        private val setIcValues: java.lang.invoke.MethodHandle,
        private val free: java.lang.invoke.MethodHandle,
        private val spotLocation: MemorySegment,
        private val preeditAttributes: MemorySegment,
    ) {
        fun setSpot(xic: MemorySegment, x: Int, y: Int) {
            val xicArgument = xic.reinterpret(1L)
            Arena.ofConfined().use { arena ->
                val point = arena.allocate(ValueLayout.JAVA_SHORT.byteSize() * 2L)
                point.set(ValueLayout.JAVA_SHORT, 0L, x.toShort())
                point.set(ValueLayout.JAVA_SHORT, ValueLayout.JAVA_SHORT.byteSize(), y.toShort())
                val nested = createNestedList.invoke(0, spotLocation, point, MemorySegment.NULL) as MemorySegment
                try {
                    setIcValues.invoke(xicArgument, preeditAttributes, nested, MemorySegment.NULL)
                } finally {
                    if (nested.address() != 0L) {
                        free.invoke(nested)
                    }
                }
            }
        }

        companion object {
            fun open(): X11Bindings {
                val arena = Arena.global()
                val lookup = SymbolLookup.libraryLookup("libX11.so.6", arena)
                val linker = Linker.nativeLinker()
                return X11Bindings(
                    createNestedList = linker.downcallHandle(
                        lookup.find("XVaCreateNestedList").orElseThrow(),
                        FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    setIcValues = linker.downcallHandle(
                        lookup.find("XSetICValues").orElseThrow(),
                        FunctionDescriptor.of(
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS,
                        ),
                    ),
                    free = linker.downcallHandle(
                        lookup.find("XFree").orElseThrow(),
                        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS),
                    ),
                    spotLocation = arena.allocateFrom("spotLocation"),
                    preeditAttributes = arena.allocateFrom("preeditAttributes"),
                )
            }
        }
    }
}
