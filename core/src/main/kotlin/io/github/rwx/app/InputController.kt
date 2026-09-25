package io.github.rwx.app

import de.fabmax.kool.input.InputStack
import de.fabmax.kool.input.KeyboardInput
import de.fabmax.kool.input.PointerInput
import io.github.rwx.mod.registry.UiRegistry
import io.github.rwx.session.GameSession
import io.github.rwx.ui.AppScreen
import io.github.rwx.ui.component.PlatformTextInputBridge

internal class InputController(
    private val gameSession: GameSession,
    private val currentScreen: () -> AppScreen,
    screenScale: () -> Float,
    private val navigateBack: () -> Unit,
    private val dismissDialog: () -> Boolean = { false },
) {
    private val legacyPointerSink = LegacyGamePointerSink(
        gameSession = gameSession,
        scaleProvider = KoolScreenScaleProvider(screenScale),
    )

    val preparedComponentName: String
        get() = legacyPointerSink::class.simpleName ?: "LegacyGamePointerSink"

    fun install() {
        KeyboardInput.addKeyListener(
            keyCode = KeyboardInput.KEY_ESC,
            name = "rwx-escape",
            filter = InputStack.KEY_FILTER_ALL,
        ) { event ->
            if (!event.isPressed) return@addKeyListener
            // While a platform IME editor is active (or was just cancelled), Esc must not leave
            // the screen — Chinese IME users cancel composition with Esc constantly.
            if (PlatformTextInputBridge.isEditing()) {
                PlatformTextInputBridge.dismissKeyboard()
                return@addKeyListener
            }
            if (dismissDialog()) return@addKeyListener
            if (!UiRegistry.cancelWorldPositionSelection()) navigateBack()
        }
        InputStack.defaultInputHandler.pointerListeners += GatedPointerListener(
            { shouldForwardKoolInputForScreen(currentScreen(), gameSession.acceptsKoolInput) },
            legacyPointerSink,
        )
        InputStack.defaultInputHandler.keyboardListeners += GatedKeyboardListener(
            { shouldForwardKoolInputForScreen(currentScreen(), gameSession.acceptsKoolInput) },
            LegacyGameKeyboardSink(gameSession),
        )
    }

    fun forwardPointerForFrame() {
        if (shouldForwardKoolInputForScreen(currentScreen(), gameSession.acceptsKoolInput)) {
            legacyPointerSink.onPointer(PointerInput.pointerState.primaryPointer)
        }
    }
}
