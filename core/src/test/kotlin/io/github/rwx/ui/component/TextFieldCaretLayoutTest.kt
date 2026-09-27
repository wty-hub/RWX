package io.github.rwx.ui.component

import de.fabmax.kool.modules.ui2.AlignmentX
import kotlin.test.Test
import kotlin.test.assertEquals

class TextFieldCaretLayoutTest {
    @Test
    fun `short text places the caret after the typed characters`() {
        val layout = textFieldCaretLayout(
            paddingStartPx = 8f,
            paddingEndPx = 0f,
            widthPx = 200f,
            innerWidthPx = 192f,
            align = AlignmentX.Start,
            charWidths = floatArrayOf(10f, 10f, 10f),
            caret = 3,
            metricsWidth = 30f,
            caretWidthPx = 1f,
            overflowMarginPx = 2f,
        )

        assertEquals(8f, layout.originX)
        assertEquals(38f, layout.caretLocalX)
    }

    @Test
    fun `a line wider than the field keeps the caret inside the box`() {
        val layout = textFieldCaretLayout(
            paddingStartPx = 8f,
            paddingEndPx = 0f,
            widthPx = 50f,
            innerWidthPx = 42f,
            align = AlignmentX.Start,
            charWidths = floatArrayOf(20f, 20f, 20f, 20f),
            caret = 4,
            metricsWidth = 80f,
            caretWidthPx = 1f,
            overflowMarginPx = 2f,
        )

        assertEquals(-32f, layout.originX)
        assertEquals(48f, layout.caretLocalX)
    }

    @Test
    fun `a click uses the scrolled text origin`() {
        val widths = floatArrayOf(20f, 20f, 20f, 20f)
        val layout = textFieldCaretLayout(
            paddingStartPx = 8f,
            paddingEndPx = 0f,
            widthPx = 50f,
            innerWidthPx = 42f,
            align = AlignmentX.Start,
            charWidths = widths,
            caret = 4,
            metricsWidth = 80f,
            caretWidthPx = 1f,
            overflowMarginPx = 2f,
        )

        assertEquals(2, caretIndexAt(10f, layout.originX, widths))
    }
}
