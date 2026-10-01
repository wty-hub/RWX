package io.github.rwx.session

import com.corrodinggames.rts.gameFramework.GameEngine
import com.corrodinggames.rts.gameFramework.ui.GameUI
import io.github.rwx.render.canvas.KoolCanvasRect
import java.util.Collections
import kotlin.math.max

/** Called only by the engine owner; matches the original pre-Minimap.draw HUD/input layout. */
fun captureGameHudLayout(engine: GameEngine): GameHudLayoutSnapshot {
    val scale = engine.screenScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val minimap = captureGameMinimapRect(engine)
    val miniBottom = engine.minimap?.getBottomY()?.toFloat() ?: 0f
    val miniWidth = engine.minimap?.width ?: engine.sidebarWidth
    val buttonHeight = ((engine.screenHeight / 14f) / scale).coerceIn(25f * scale, 40f * scale)
    val unselectHeight = (buttonHeight.toDouble() * 0.9).toFloat().toInt().toFloat()
    val ui = engine.gameUI
    val hasUnselectSlot = !GameUI.a && !(ui?.editorOrBuilder != null && ui.selectedUnitCount == 1 && ui.editorOrBuilder.isSelected)
    val unselect = if (hasUnselectSlot && (ui?.selectedUnitCount ?: 0) > 0) {
        val left = ((engine.screenWidth - miniWidth) + 2f).toInt().toFloat()
        KoolCanvasRect(left, miniBottom + 2f, left + (miniWidth - 4f).toInt(), miniBottom + 2f + unselectHeight)
    } else null
    var actionTop = if (!GameUI.bR) miniBottom + 2f else (minimap?.top ?: 0f)
    var rowPitch = buttonHeight + 2f
    if (GameUI.bO) { actionTop += 3f; rowPitch += 15f * scale + 4f }
    if (hasUnselectSlot) actionTop += (buttonHeight.toDouble() * 0.9).toFloat() + 2f
    val groups = if (engine.settingsEngine?.showUnitGroups == true) {
        val top = (engine.currentScreenHeightPixels - 30f * scale).toInt().toFloat()
        val left = ((engine.screenWidth - engine.sidebarWidth) + 10f).toInt().toFloat()
        val slotWidth = (engine.sidebarWidth - 20f).toInt() / 3
        List(3) { index -> KoolCanvasRect(left + index * slotWidth, top,
            left + index * slotWidth + max(0, slotWidth - 5), top + (31f * scale).toInt()) }
    } else emptyList()
    return GameHudLayoutSnapshot(minimap, unselect, actionTop, rowPitch,
        Collections.unmodifiableList(groups), scale, !GameUI.bR && !GameUI.bO)
}

/** Snapshot the actual minimap geometry; the packet uses this again AFTER original Minimap.draw. */
fun captureGameMinimapRect(engine: GameEngine): KoolCanvasRect? {
    val minimap = engine.minimap ?: return null
    if (minimap.width <= 0f || minimap.height <= 0f) return null
    val left = minimap.minimapBoundsRect.a.toFloat()
    val top = minimap.getBottomY() - minimap.height
    return KoolCanvasRect(left, top, left + minimap.width, top + minimap.height)
}
