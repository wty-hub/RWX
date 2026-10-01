package io.github.rwx.kool

import io.github.rwx.render.canvas.KoolCanvasViewport
import io.github.rwx.session.GameCameraSnapshot
import io.github.rwx.session.GamePointerFrameContext
import io.github.rwx.render.canvas.KoolCanvasRect

/** Reconstruct the world point seen at the event, then express it in the owner's current camera. */
internal fun projectSeenPointer(
    seen: GameCameraSnapshot?,
    current: GameCameraSnapshot?,
    viewport: KoolCanvasViewport,
    x: Float,
    y: Float,
    surfaceViewport: KoolCanvasViewport? = null,
): Pair<Float, Float> {
    val (seenX, seenY) = GamePointerFrameContext(seen, surfaceViewport).positionInSeenViewport(x, y)
    val seenHud = seen?.hudLayout
    val currentHud = current?.hudLayout
    if (seenHud != null && currentHud != null) {
        mapHudRect(seenHud.minimap, currentHud.minimap, seenX, seenY)?.let { return it }
        mapHudRect(seenHud.unselectButton, currentHud.unselectButton, seenX, seenY)?.let { return it }
        seenHud.unitGroupButtons.forEachIndexed { index, rect ->
            mapHudRect(rect, currentHud.unitGroupButtons.getOrNull(index), seenX, seenY)?.let { return it }
        }
    }
    if (current != null && seen != null && seen.zoom.isFinite() && seen.zoom > 0f &&
        current.zoom.isFinite() && current.zoom > 0f && seenX < seen.viewport.width - seen.sidebarWidth) {
        return ((seenX / seen.zoom + seen.x) - current.x) * current.zoom to
            ((seenY / seen.zoom + seen.y) - current.y) * current.zoom
    }
    // Keep a sidebar click within the current right-anchored sidebar even when its width does not
    // scale with the framebuffer. Globally scaling X can move a visible HUD click into the world.
    val hudX = if (seen != null && seen.sidebarWidth > 0f &&
        seenX >= seen.viewport.width - seen.sidebarWidth && current != null) {
        val currentSidebar = current.sidebarWidth.coerceIn(0f, viewport.width.coerceAtLeast(0).toFloat())
        viewport.width - currentSidebar +
            (seenX - (seen.viewport.width - seen.sidebarWidth)) * currentSidebar / seen.sidebarWidth
    } else seenX * viewport.width / (seen?.viewport?.width ?: viewport.width).coerceAtLeast(1)
    val hudY = if (seenHud != null && currentHud != null && seenHud.standardSidebar && currentHud.standardSidebar) {
        if (seenY >= seenHud.actionTop && seenHud.actionRowPitch > 0f) {
            currentHud.actionTop + (seenY - seenHud.actionTop) * currentHud.actionRowPitch / seenHud.actionRowPitch
        } else {
            val seenBottom = seenHud.minimap?.bottom ?: 0f
            val currentBottom = currentHud.minimap?.bottom ?: 0f
            currentBottom + (seenY - seenBottom) * currentHud.screenScale / seenHud.screenScale.coerceAtLeast(0.001f)
        }
    } else {
        // Older/non-recording backends and alternate action panels have no ordinary sidebar
        // anchor metadata. Exact scrolling/reflow across a changed action layout is unverified.
        seenY * viewport.height / (seen?.viewport?.height ?: viewport.height).coerceAtLeast(1)
    }
    return hudX to hudY
}

private fun mapHudRect(seen: KoolCanvasRect?, current: KoolCanvasRect?, x: Float, y: Float): Pair<Float, Float>? {
    if (seen == null || current == null || seen.isEmpty || current.isEmpty ||
        x < seen.left || x > seen.right || y < seen.top || y > seen.bottom) return null
    return current.left + (x - seen.left) * current.width / seen.width to
        current.top + (y - seen.top) * current.height / seen.height
}
