package io.github.rwx

import org.lwjgl.awt.AWT
import org.lwjgl.system.macosx.ObjCRuntime
import org.lwjgl.system.JNI
import java.awt.Canvas
import java.awt.Point
import java.awt.Rectangle
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import javax.swing.SwingUtilities

/** Reapply JAWT's layer bounds after AWTVK replaces the layer without changing Canvas bounds. */
internal object MacMetalLayerLayout {
    private val rect = MemoryLayout.structLayout(
        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
        ValueLayout.JAVA_DOUBLE, ValueLayout.JAVA_DOUBLE,
    )
    private val setBounds by lazy {
        Linker.nativeLinker().downcallHandle(
            MemorySegment.ofAddress(ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend")),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, rect),
        )
    }

    fun refresh(canvas: Canvas) {
        var bounds = Rectangle()
        SwingUtilities.invokeAndWait {
            val root = SwingUtilities.getRootPane(canvas)
            val origin = SwingUtilities.convertPoint(canvas, Point(), root)
            bounds = Rectangle(origin.x, origin.y, canvas.width, canvas.height)
        }
        AWT(canvas).use { drawingSurface ->
            Arena.ofConfined().use { arena ->
                val send = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend")
                val layer = JNI.invokePPP(drawingSurface.platformInfo, ObjCRuntime.sel_getUid("layer"), send)
                val windowLayer = JNI.invokePPP(drawingSurface.platformInfo, ObjCRuntime.sel_getUid("windowLayer"), send)
                check(layer != 0L && windowLayer != 0L) { "JAWT Metal layer is not attached" }
                // Keep the Metal layer above AWT's own backing layer after window changes.
                JNI.invokePPPV(windowLayer, ObjCRuntime.sel_getUid("addSublayer:"), layer, send)
                val value = arena.allocate(rect)
                value.set(ValueLayout.JAVA_DOUBLE, 0, bounds.x.toDouble())
                value.set(ValueLayout.JAVA_DOUBLE, 8, bounds.y.toDouble())
                value.set(ValueLayout.JAVA_DOUBLE, 16, bounds.width.toDouble())
                value.set(ValueLayout.JAVA_DOUBLE, 24, bounds.height.toDouble())
                // AWTSurfaceLayers.setBounds: converts AWT's top-left coordinates to Cocoa's
                // bottom-left coordinates and updates the attached CAMetalLayer frame.
                setBounds.invoke(
                    MemorySegment.ofAddress(drawingSurface.platformInfo),
                    MemorySegment.ofAddress(ObjCRuntime.sel_getUid("setBounds:")), value,
                )
            }
        }
        org.lwjgl.awt.MacOSX.caFlush()
    }
}
