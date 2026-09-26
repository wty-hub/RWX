package io.github.rwx

import java.awt.Component
import java.awt.Window
import javax.swing.SwingUtilities

/**
 * Whether this component's Swing window (or its owner frame) is the active application window.
 *
 * A component with no ancestor window is treated as active so startup and headless tests can still
 * request in-window focus. Cross-application [java.awt.Component.requestFocus] / [Window.toFront]
 * must not run when this is false: they yank RWXX back from the background.
 */
internal fun isSwingComponentHostActive(component: Component?): Boolean {
    val window = when (component) {
        is Window -> component
        null -> return false
        else -> SwingUtilities.getWindowAncestor(component)
    } ?: return true
    var current: Window? = window
    while (current != null) {
        if (current.isActive) return true
        current = current.owner
    }
    return false
}
