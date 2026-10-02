package io.github.rwx

import java.awt.Window
import java.lang.reflect.Proxy
import javax.swing.JFrame
import javax.swing.SwingUtilities

/** Uses the JDK's Cocoa fullscreen events, including the native green button and menu action. */
internal class MacNativeFullscreen(
    private val frame: JFrame,
    onTransitionStarted: (Boolean) -> Unit,
    onTransitionCompleted: () -> Unit,
) : AutoCloseable {
    // Reflect the macOS-specific JDK APIs so Windows/Linux builds remain portable.
    // Launchers export java.desktop/com.apple.eawt to this unnamed module.
    private val utilities = Class.forName("com.apple.eawt.FullScreenUtilities")
    private val listenerType = Class.forName("com.apple.eawt.FullScreenListener")
    private val applicationType = Class.forName("com.apple.eawt.Application")
    private val application = applicationType.getMethod("getApplication").invoke(null)
    private val toggle = applicationType.getMethod("requestToggleFullScreen", Window::class.java)
    private val state = MacFullscreenState(
        toggle = { toggle.invoke(application, frame) },
        onStarted = onTransitionStarted,
        onCompleted = onTransitionCompleted,
    )
    val isFullscreenOrTransitioning: Boolean get() = state.fullscreen || state.transitioning

    private val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { proxy, method, args ->
        when (method.name) {
            "windowEnteringFullScreen" -> state.started(true)
            "windowEnteredFullScreen" -> state.completed(true)
            "windowExitingFullScreen" -> state.started(false)
            "windowExitedFullScreen" -> state.completed(false)
            "hashCode" -> return@newProxyInstance System.identityHashCode(proxy)
            "equals" -> return@newProxyInstance proxy === args?.firstOrNull()
            "toString" -> return@newProxyInstance "RWX macOS fullscreen listener"
        }
        null
    }

    init {
        check(SwingUtilities.isEventDispatchThread())
        utilities.getMethod("setWindowCanFullScreen", Window::class.java, Boolean::class.javaPrimitiveType)
            .invoke(null, frame, true)
        utilities.getMethod("addFullScreenListenerTo", Window::class.java, listenerType)
            .invoke(null, frame, listener)
    }

    fun request(fullscreen: Boolean) {
        check(SwingUtilities.isEventDispatchThread())
        state.request(fullscreen)
    }

    override fun close() {
        utilities.getMethod("removeFullScreenListenerFrom", Window::class.java, listenerType)
            .invoke(null, frame, listener)
    }
}

/** EDT-owned state: serialize asynchronous Cocoa toggles and accept changes from the green button. */
internal class MacFullscreenState(
    private val toggle: () -> Unit,
    private val onStarted: (Boolean) -> Unit,
    private val onCompleted: () -> Unit,
) {
    var fullscreen = false
        private set
    var transitioning = false
        private set
    private var desired = false

    fun request(value: Boolean) {
        desired = value
        if (!transitioning && fullscreen != desired) {
            transitioning = true
            toggle()
        }
    }

    fun started(value: Boolean) {
        transitioning = true
        desired = value
        onStarted(value)
    }

    fun completed(value: Boolean) {
        fullscreen = value
        transitioning = false
        onCompleted()
        request(desired)
    }
}
