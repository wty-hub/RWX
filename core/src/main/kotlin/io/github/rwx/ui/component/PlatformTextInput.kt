package io.github.rwx.ui.component

import de.fabmax.kool.input.KeyCode
import de.fabmax.kool.modules.ui2.AlignmentX
import de.fabmax.kool.modules.ui2.TextField
import de.fabmax.kool.modules.ui2.TextFieldScope
import de.fabmax.kool.modules.ui2.UiNode
import de.fabmax.kool.modules.ui2.UiScope
import de.fabmax.kool.modules.ui2.remember
import de.fabmax.kool.modules.ui2.selectionRange
import de.fabmax.kool.util.Font
import de.fabmax.kool.util.TextCaretNavigation
import de.fabmax.kool.util.TextMetrics
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

/**
 * Caret rectangle in Kool window pixels. Desktop uses it to place the input-method candidate
 * window; the origin is the top-left of the caret and [height] reaches its baseline.
 */
data class PlatformCaretRect(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
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
    val caretRect: PlatformCaretRect? = null,
    val fieldRect: PlatformCaretRect? = null,
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

    /**
     * Runs after Esc leaves a focused text field that has no in-progress input-method composition.
     * In-game chat uses this to close the dialog; unfocusing the field alone left the window open.
     */
    @Volatile
    var onEscape: (() -> Unit)? = null

    /**
     * The platform editor consumed a submit key (Enter) before the game could see it.
     * The match should ignore that key until its key-up arrives, so the same press cannot reopen chat.
     */
    @Volatile
    var onSubmitKey: ((KeyCode) -> Unit)? = null

    /**
     * A key the platform editor handled was released. The match has to see that key-up even while a
     * dialog is swallowing keyboard input, or [io.github.rwx.session.GameSession.suppressKeyUntilRelease]
     * stays latched and the next Enter never opens chat.
     */
    @Volatile
    var onKeyReleased: ((KeyCode) -> Unit)? = null

    /**
     * The next text field to take focus should ignore Enter until that key is released. Chat is
     * opened with Enter; the same press must not submit an empty message once the field focuses.
     */
    @Volatile
    private var swallowSubmitUntilRelease: Boolean = false

    fun armSwallowSubmitUntilRelease() {
        swallowSubmitUntilRelease = true
    }

    fun consumeSwallowSubmitUntilRelease(): Boolean {
        val armed = swallowSubmitUntilRelease
        swallowSubmitUntilRelease = false
        return armed
    }

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
            val caretForHit = caret.value.coerceIn(0, text.length)
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
                val index = node.platformCaretIndex(modifier.font, text, event.position.x, caretForHit, modifier.textAlignX)
                if (event.pointer.leftButtonRepeatedClickCount > 1) {
                    val bounds = text.wordBoundsAt(index)
                    requestCaret(bounds.first, bounds.second)
                } else {
                    requestCaret(index, index)
                }
            }
            modifier.onDragStart += { event ->
                val index = node.platformCaretIndex(modifier.font, text, event.position.x, caretForHit, modifier.textAlignX)
                dragAnchor.value = index
                requestCaret(index, index)
            }
            modifier.onDrag += { event ->
                val anchor = dragAnchor.value.coerceAtLeast(0)
                val index = node.platformCaretIndex(modifier.font, text, event.position.x, caretForHit, modifier.textAlignX)
                requestCaret(anchor, index)
            }
        }
    }

    if (isFocused) {
        val caretIndex = if (caretOwned) caret.value.coerceIn(0, text.length) else text.length
        val fieldNode = textField as? UiNode
        val caretRect = fieldNode?.let { node ->
            platformCaretRect(node, modifier.font, text, caretIndex, modifier.textAlignX)
        }
        val fieldRect = fieldNode?.let { node ->
            PlatformCaretRect(
                x = node.leftPx,
                y = node.topPx,
                width = node.widthPx.coerceAtLeast(1f),
                height = node.heightPx.coerceAtLeast(1f),
            )
        }
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
                caretRect = caretRect,
                fieldRect = fieldRect,
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

private fun UiScope.platformCaretRect(
    node: UiNode,
    font: Font,
    text: String,
    caret: Int,
    alignX: AlignmentX,
): PlatformCaretRect {
    val widths = FloatArray(text.length) { index -> font.charWidth(text[index]) }
    val metricsWidth = if (text.isEmpty()) 0f else font.textDimensions(text, TextMetrics()).width
    val layout = textFieldCaretLayout(
        paddingStartPx = node.paddingStartPx,
        paddingEndPx = node.paddingEndPx,
        widthPx = node.widthPx,
        innerWidthPx = node.innerWidthPx,
        align = alignX,
        charWidths = widths,
        caret = caret,
        metricsWidth = metricsWidth,
        caretWidthPx = 1f.dp.px,
        overflowMarginPx = 2f.dp.px,
    )
    // Same caret box Kool draws: font size plus a few dp, vertically centered in the field.
    val caretHeight = (font.sizePts + 4f).dp.px
    val top = ((node.heightPx - caretHeight) / 2f).coerceAtLeast(0f)
    return PlatformCaretRect(
        x = node.leftPx + layout.caretLocalX,
        y = node.topPx + top,
        width = 1f,
        height = caretHeight.coerceIn(1f, node.heightPx.coerceAtLeast(1f)),
    )
}

private fun UiNode.platformCaretIndex(
    font: Font,
    text: String,
    localX: Float,
    caret: Int,
    alignX: AlignmentX,
): Int {
    val widths = FloatArray(text.length) { index -> font.charWidth(text[index]) }
    val metricsWidth = if (text.isEmpty()) 0f else font.textDimensions(text, TextMetrics()).width
    val layout = textFieldCaretLayout(
        paddingStartPx = paddingStartPx,
        paddingEndPx = paddingEndPx,
        widthPx = widthPx,
        innerWidthPx = innerWidthPx,
        align = alignX,
        charWidths = widths,
        caret = caret,
        metricsWidth = metricsWidth,
        caretWidthPx = 1f.dp.px,
        overflowMarginPx = 2f.dp.px,
    )
    return caretIndexAt(localX, layout.originX, widths)
}

/**
 * Caret placement that follows Kool's text field: character advances, then the same overflow
 * scroll that keeps the caret inside the field once the line is wider than the box.
 */
internal class TextFieldCaretLayout(
    val originX: Float,
    val caretLocalX: Float,
)

internal fun textFieldCaretLayout(
    paddingStartPx: Float,
    paddingEndPx: Float,
    widthPx: Float,
    innerWidthPx: Float,
    align: AlignmentX,
    charWidths: FloatArray,
    caret: Int,
    metricsWidth: Float,
    caretWidthPx: Float,
    overflowMarginPx: Float,
): TextFieldCaretLayout {
    val end = caret.coerceIn(0, charWidths.size)
    var prefix = 0f
    for (index in 0 until end) {
        prefix += charWidths[index]
    }
    val originBase = when (align) {
        AlignmentX.Start -> paddingStartPx
        AlignmentX.Center -> (widthPx - metricsWidth) / 2f
        AlignmentX.End -> widthPx - metricsWidth - caretWidthPx - paddingEndPx
    }
    val caretWithoutOverflow = originBase + prefix
    val overflow = if (metricsWidth < innerWidthPx) {
        0f
    } else when {
        caretWithoutOverflow < 0f -> -caretWithoutOverflow
        caretWithoutOverflow > widthPx -> widthPx - caretWithoutOverflow - overflowMarginPx
        else -> 0f
    }
    val originX = originBase + overflow
    val caretLocalX = (originX + prefix).coerceIn(0f, widthPx.coerceAtLeast(0f))
    return TextFieldCaretLayout(originX, caretLocalX)
}

internal fun caretIndexAt(localX: Float, originX: Float, charWidths: FloatArray): Int {
    var x = originX
    for (index in charWidths.indices) {
        val width = charWidths[index]
        if (x + width >= localX) {
            return if (abs(x - localX) < abs(x + width - localX)) index else index + 1
        }
        x += width
    }
    return charWidths.size
}

private fun String.wordBoundsAt(index: Int): Pair<Int, Int> {
    var start = TextCaretNavigation.moveWordLeft(this, index)
    while (start < length && this[start].isWhitespace()) start++
    val end = TextCaretNavigation.moveWordRight(this, start)
    return start to end
}
