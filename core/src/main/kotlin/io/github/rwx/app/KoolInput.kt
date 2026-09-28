package io.github.rwx.app

import com.corrodinggames.rts.gameFramework.GameEngine
import de.fabmax.kool.KoolContext
import de.fabmax.kool.input.InputStack
import de.fabmax.kool.input.KeyEvent
import de.fabmax.kool.input.Pointer
import de.fabmax.kool.input.PointerState
import io.github.rwx.input.KoolKeyCodeMapping
import io.github.rwx.logger
import io.github.rwx.mod.api.WorldPosition
import io.github.rwx.mod.registry.UiRegistry
import io.github.rwx.session.GameSession

fun interface KoolPointerScaleProvider {
    fun pointerToGameScale(): Float
}

fun interface KoolLegacyPointerSink {
    fun onPointer(pointer: Pointer)
}

class LegacyGamePointerSink(
    private val gameSession: GameSession,
    private val scaleProvider: KoolPointerScaleProvider = KoolPointerScaleProvider { 1.0f },
    private val blockWorldWheel: () -> Boolean = { false },
) : KoolLegacyPointerSink, InputStack.PointerListener {
    private var activePointerId = NO_BUTTON_ID
    private var suppressPointerUntilRelease = false
    private var suppressPointerAfterFocusLoss = false
    private var lastScreenX = 0f
    private var lastScreenY = 0f

    override fun handlePointer(pointerState: PointerState, ctx: KoolContext) {
        onPointer(pointerState.primaryPointer)
    }

    @Synchronized
    override fun onPointer(pointer: Pointer) {
        val scale = scaleProvider.pointerToGameScale().takeIf { it.isFinite() && it > 0.0f } ?: 1.0f
        val screenX = pointer.pos.x * scale
        val screenY = pointer.pos.y * scale
        lastScreenX = screenX
        lastScreenY = screenY
        if (suppressPointerAfterFocusLoss) {
            if (pointer.hasLegacyButtonPress()) {
                // A fresh click after refocusing starts a new gesture. A button that merely
                // remains down belongs to the gesture we released on focus loss.
                suppressPointerAfterFocusLoss = false
            } else {
                if (!pointer.hasLegacyButtonActivity()) suppressPointerAfterFocusLoss = false
                return
            }
        }
        if (handleWorldPositionSelection(pointer, screenX, screenY)) return
        // A modal window (players, chat, …) scrolls itself. The same wheel must not zoom the map.
        if (!blockWorldWheel()) {
            pointer.scroll.y.takeIf { it != 0f }?.let { scroll ->
                gameSession.submitMouseWheel((scroll * 120f).toInt())
            }
        }
        val pointerId = pointer.legacyButtonId()
        if (pointerId == NO_BUTTON_ID) {
            if (activePointerId != NO_BUTTON_ID) {
                logger.info("RWXInput") {
                    "pointer release synthesized id=$activePointerId xy=${screenX.toInt()},${screenY.toInt()}"
                }
                gameSession.submitPointer(
                    screenX = screenX,
                    screenY = screenY,
                    isDown = false,
                    pointerId = activePointerId,
                )
                activePointerId = NO_BUTTON_ID
            }
            if (pointer.isValid) {
                gameSession.movePointer(screenX, screenY)
            }
            return
        }
        if (activePointerId != NO_BUTTON_ID && activePointerId != pointerId) {
            logger.info("RWXInput") {
                "pointer switch from=$activePointerId to=$pointerId xy=${screenX.toInt()},${screenY.toInt()}"
            }
            gameSession.submitPointer(
                screenX = screenX,
                screenY = screenY,
                isDown = false,
                pointerId = activePointerId,
            )
        }
        val isDown = pointer.isValid && pointer.isLegacyButtonDown()
        if (isDown && activePointerId != pointerId) {
            logger.info("RWXInput") { "pointer down id=$pointerId xy=${screenX.toInt()},${screenY.toInt()}" }
        } else if (!isDown && activePointerId == pointerId) {
            logger.info("RWXInput") { "pointer up id=$pointerId xy=${screenX.toInt()},${screenY.toInt()}" }
        }
        gameSession.submitPointer(
            screenX = screenX,
            screenY = screenY,
            isDown = isDown,
            pointerId = pointerId,
        )
        activePointerId = if (isDown) pointerId else NO_BUTTON_ID
    }

    @Synchronized
    fun resetOnHostFocusLost() {
        val pointerId = activePointerId
        if (pointerId != NO_BUTTON_ID) {
            activePointerId = NO_BUTTON_ID
            suppressPointerAfterFocusLoss = true
            gameSession.submitPointer(lastScreenX, lastScreenY, false, pointerId)
        }
    }

    private fun handleWorldPositionSelection(pointer: Pointer, screenX: Float, screenY: Float): Boolean {
        if (!UiRegistry.hasActiveWorldPositionSelection()) {
            if (!suppressPointerUntilRelease) return false
            if (!pointer.hasLegacyButtonActivity()) suppressPointerUntilRelease = false
            return true
        }

        suppressPointerUntilRelease = true
        when {
            pointer.isRightButtonPressed -> UiRegistry.cancelWorldPositionSelection()
            pointer.isLeftButtonPressed -> {
                val engine = GameEngine.getInstance()
                val zoom = engine.zoom
                if (zoom.isFinite() && zoom > 0f && screenX.isFinite() && screenY.isFinite()) {
                    UiRegistry.selectWorldPosition(
                        WorldPosition(
                            x = screenX / zoom + engine.viewpointXSnapped,
                            y = screenY / zoom + engine.viewpointYSnapped,
                        )
                    )
                }
            }
        }
        return true
    }

    private fun Pointer.hasLegacyButtonActivity(): Boolean =
        isLeftButtonDown || isLeftButtonPressed || isLeftButtonReleased ||
                isRightButtonDown || isRightButtonPressed || isRightButtonReleased ||
                isMiddleButtonDown || isMiddleButtonPressed || isMiddleButtonReleased

    private fun Pointer.hasLegacyButtonPress(): Boolean =
        isLeftButtonPressed || isRightButtonPressed || isMiddleButtonPressed

    private fun Pointer.legacyButtonId(): Int = when {
        isLeftButtonDown || isLeftButtonPressed || isLeftButtonReleased -> LEFT_BUTTON_ID
        isRightButtonDown || isRightButtonPressed || isRightButtonReleased -> RIGHT_BUTTON_ID
        isMiddleButtonDown || isMiddleButtonPressed || isMiddleButtonReleased -> MIDDLE_BUTTON_ID
        else -> NO_BUTTON_ID
    }

    private fun Pointer.isLegacyButtonDown(): Boolean =
        isLeftButtonDown || isRightButtonDown || isMiddleButtonDown

    private companion object {
        const val NO_BUTTON_ID: Int = -1
        const val LEFT_BUTTON_ID: Int = 1
        const val RIGHT_BUTTON_ID: Int = 2
        const val MIDDLE_BUTTON_ID: Int = 3
    }
}

class LegacyGameKeyboardSink(
    private val gameSession: GameSession,
) : InputStack.KeyboardListener {
    private val pressedKeys = linkedSetOf<Int>()
    private val suppressRepeatsUntilRelease = mutableSetOf<Int>()

    @Synchronized
    override fun handleKeyboard(keyEvents: List<KeyEvent>, ctx: KoolContext) {
        keyEvents.forEach { event ->
            if (event.isCharTyped) {
                return@forEach
            }
            val androidKeyCode = event.androidKeyCode()
            if (androidKeyCode == null) {
                if (event.isPressed) {
                    logger.info("RWXInput") { "kool key dropped unmapped key=${event.keyCode}" }
                }
                return@forEach
            }
            event.isConsumed = forwardAndroidKey(
                androidKeyCode = androidKeyCode,
                isPressed = event.isPressed,
                isRepeated = event.isRepeated,
                isReleased = event.isReleased,
            )
        }
    }

    @Synchronized
    internal fun forwardAndroidKey(
        androidKeyCode: Int,
        isPressed: Boolean = false,
        isRepeated: Boolean = false,
        isReleased: Boolean = false,
    ): Boolean {
        when {
            isPressed || isRepeated -> {
                if (isPressed) {
                    logger.info("RWXInput") { "kool key down android=$androidKeyCode" }
                    suppressRepeatsUntilRelease.remove(androidKeyCode)
                }
                if (androidKeyCode !in suppressRepeatsUntilRelease) {
                    pressedKeys += androidKeyCode
                    gameSession.submitKey(androidKeyCode, true)
                }
            }

            isReleased -> {
                logger.info("RWXInput") { "kool key up android=$androidKeyCode" }
                pressedKeys -= androidKeyCode
                suppressRepeatsUntilRelease -= androidKeyCode
                gameSession.submitKey(androidKeyCode, false)
            }

            else -> return false
        }
        return true
    }

    @Synchronized
    fun resetOnHostFocusLost() {
        suppressRepeatsUntilRelease += pressedKeys
        pressedKeys.forEach { gameSession.submitKey(it, false) }
        pressedKeys.clear()
    }

    private fun KeyEvent.androidKeyCode(): Int? = KoolKeyCodeMapping.androidKeyCode(this)
}

class GatedPointerListener(
    private val isEnabled: () -> Boolean,
    private val delegate: InputStack.PointerListener,
) : InputStack.PointerListener {
    override fun handlePointer(pointerState: PointerState, ctx: KoolContext) {
        if (isEnabled()) {
            delegate.handlePointer(pointerState, ctx)
        }
    }
}

class GatedKeyboardListener(
    private val isEnabled: () -> Boolean,
    private val delegate: InputStack.KeyboardListener,
) : InputStack.KeyboardListener {
    override fun handleKeyboard(keyEvents: List<KeyEvent>, ctx: KoolContext) {
        if (isEnabled()) {
            delegate.handleKeyboard(keyEvents, ctx)
        }
    }
}
