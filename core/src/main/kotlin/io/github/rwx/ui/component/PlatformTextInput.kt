package io.github.rwx.ui.component

import de.fabmax.kool.modules.ui2.TextField
import de.fabmax.kool.modules.ui2.TextFieldScope
import de.fabmax.kool.modules.ui2.UiNode
import de.fabmax.kool.modules.ui2.UiScope
import de.fabmax.kool.modules.ui2.remember
import de.fabmax.kool.modules.ui2.selectionRange
import de.fabmax.kool.util.Font
import de.fabmax.kool.util.TextCaretNavigation
import kotlin.math.abs

/**
 * A caret / selection position the platform editor should adopt because the user clicked or dragged
 * inside the Kool text field. [id] makes each request a one-shot: the controller applies it once.
 */
data class PlatformCaretRequest(
    val id: Long,
    val selectionStart: Int,
    val caret: Int,
)

data class PlatformTextInputRequest(
    val owner: Any,
    val text: String,
    val hint: String,
    val maxLength: Int,
    val onChange: (String) -> Unit,
    val onEnter: ((String) -> Unit)?,
    val onCancel: (() -> Unit)? = null,
    val caretRequest: PlatformCaretRequest? = null,
    val onSelectionChanged: ((selectionStart: Int, caret: Int) -> Unit)? = null,
)

interface PlatformTextInputController {
    /**
     * True when the platform editor owns the text, the caret and the selection and the Kool field
     * only displays them (true for the desktop bridge). Controllers that show their own native
     * editor next to the Kool field (Android) leave this false and keep Kool's own editing.
     */
    val ownsCaret: Boolean get() = false

    /** True while a Kool text field has an active platform editing session. */
    val isEditing: Boolean get() = false

    fun showOrUpdate(request: PlatformTextInputRequest)
    fun hide(owner: Any)
    fun dismissKeyboard() = Unit
}

object PlatformTextInputBridge {
    @Volatile
    private var controller: PlatformTextInputController? = null

    fun install(controller: PlatformTextInputController) {
        this.controller = controller
    }

    fun uninstall(controller: PlatformTextInputController) {
        if (this.controller === controller) {
            this.controller = null
        }
    }

    /** Whether the installed controller drives the caret and selection of the Kool text field. */
    fun ownsCaret(): Boolean = controller?.ownsCaret == true

    /** Whether a platform text editor is currently active for a Kool field. */
    fun isEditing(): Boolean = controller?.isEditing == true

    internal fun showOrUpdate(request: PlatformTextInputRequest): Boolean {
        val activeController = controller ?: return false
        activeController.showOrUpdate(request)
        return true
    }

    internal fun hide(owner: Any) {
        controller?.hide(owner)
    }

    internal fun dismissKeyboard() {
        controller?.dismissKeyboard()
    }
}

/**
 * provide a native editor for the active field.
 *
 * When the installed controller owns the caret, the Kool field stops owning the editing state: the
 * native editor is the single source of truth for text, caret and selection, and this composable
 * mirrors them into the Kool field for rendering. Clicks and drags inside the field are translated
 * into caret requests for that editor.
 */
fun UiScope.RwxTextField(
    text: String = "",
    scopeName: String? = null,
    block: TextFieldScope.() -> Unit,
): TextFieldScope {
    val owner = remember(Any())
    val wasFocused = remember(false)
    // Kool stores remembered values in per-type slots that are consumed in call order and rewound
    // every frame (WeakMemory), so this composable must call remember() the same number of times,
    // in the same order, on every frame -- independent of whether the platform owns the caret.
    val caret = remember(text.length)
    val selection = remember(text.length)
    val pendingCaret = remember(null as PlatformCaretRequest?)
    val requestId = remember(0L)
    val dragAnchor = remember(-1)
    val reported = remember(PlatformSelection())

    val textField = TextField(text, scopeName, block)
    val isFocused = textField.isFocused.use()
    val modifier = textField.modifier
    val caretOwned = PlatformTextInputBridge.ownsCaret()

    val reportedSelection = reported.use()
    if (reportedSelection.caret >= 0) {
        if (caret.value != reportedSelection.caret) caret.value = reportedSelection.caret
        if (selection.value != reportedSelection.selectionStart) selection.value = reportedSelection.selectionStart
    }

    if (caretOwned) {
        val currentCaret = caret.use().coerceIn(0, text.length)
        val currentSelection = selection.use().coerceIn(0, text.length)
        // The native editor is authoritative: always feed its caret and selection back for rendering.
        modifier.selectionRange(currentSelection, currentCaret)

        (textField as? UiNode)?.let { node ->
            fun requestCaret(selectionStart: Int, caretPosition: Int) {
                val start = selectionStart.coerceIn(0, text.length)
                val position = caretPosition.coerceIn(0, text.length)
                selection.value = start
                caret.value = position
                // Keep the buffered report in sync too, otherwise a report that is still in flight
                // from the editor would overwrite the click we just registered for one frame.
                reported.value.update(position, start)
                requestId.value += 1
                pendingCaret.value = PlatformCaretRequest(requestId.value, start, position)
            }

            modifier.onClick += { event ->
                val index = node.platformCaretIndex(modifier.font, text, event.position.x)
                if (event.pointer.leftButtonRepeatedClickCount > 1) {
                    val bounds = text.wordBoundsAt(index)
                    requestCaret(bounds.first, bounds.second)
                } else {
                    requestCaret(index, index)
                }
            }
            modifier.onDragStart += { event ->
                val index = node.platformCaretIndex(modifier.font, text, event.position.x)
                dragAnchor.value = index
                requestCaret(index, index)
            }
            modifier.onDrag += { event ->
                val anchor = dragAnchor.value.coerceAtLeast(0)
                val index = node.platformCaretIndex(modifier.font, text, event.position.x)
                requestCaret(anchor, index)
            }
        }
    }

    if (isFocused) {
        PlatformTextInputBridge.showOrUpdate(
            PlatformTextInputRequest(
                owner = owner.value,
                text = text,
                hint = modifier.hint,
                maxLength = modifier.maxLength,
                onChange = { modifier.onChange?.invoke(it) },
                onEnter = modifier.onEnterPressed,
                // Unfocus the Kool field without synthesizing Esc into the global input stack:
                // while the platform editor holds AWT focus the Kool canvas is unfocused, so a
                // synthetic Esc would miss the focused TextField and hit the app-wide back handler
                // (leave battleroom / jump to main menu) instead.
                onCancel = { textField.surface.requestFocus(null) },
                caretRequest = if (caretOwned) pendingCaret.use() else null,
                onSelectionChanged = if (caretOwned) {
                    { selectionStart, caretPosition -> reported.value.update(caretPosition, selectionStart) }
                } else {
                    null
                },
            )
        )
    } else if (wasFocused.value) {
        PlatformTextInputBridge.hide(owner.value)
    }
    wasFocused.value = isFocused
    return textField
}

/**
 * Selection reported by the platform editor. The editor calls back from the platform UI thread, so
 * the values are buffered here and picked up by the next composition instead of writing UI state
 * from a foreign thread.
 */
private class PlatformSelection {
    @Volatile
    var caret: Int = -1

    @Volatile
    var selectionStart: Int = -1

    fun update(caret: Int, selectionStart: Int) {
        this.caret = caret
        this.selectionStart = selectionStart
    }
}

private fun UiNode.platformCaretIndex(font: Font, text: String, localX: Float): Int {
    var x = paddingStartPx
    for (index in text.indices) {
        val width = font.charWidth(text[index])
        if (x + width >= localX) {
            return if (abs(x - localX) < abs(x + width - localX)) index else index + 1
        }
        x += width
    }
    return text.length
}

private fun String.wordBoundsAt(index: Int): Pair<Int, Int> {
    var start = TextCaretNavigation.moveWordLeft(this, index)
    while (start < length && this[start].isWhitespace()) start++
    val end = TextCaretNavigation.moveWordRight(this, start)
    return start to end
}
