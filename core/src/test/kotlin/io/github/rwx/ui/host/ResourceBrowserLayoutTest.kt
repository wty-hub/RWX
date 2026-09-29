package io.github.rwx.ui.host

import de.fabmax.kool.modules.ui2.Dp
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.component.CyberBackdropCaptionBand
import io.github.rwx.ui.component.menuPanelFrameHeight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceBrowserLayoutTest {
    @Test
    fun `content column never spills out of the panel`() {
        for (isAndroid in listOf(false, true)) {
            val minListHeight = if (isAndroid) 120f else UiTheme.Layout.modsMinViewportHeight.value
            // Captions are only drawn on surfaces tall enough to carry the frame band.
            for (viewportHeight in 300..1800 step 10) {
                for (showCaptions in listOf(false, viewportHeight >= 480)) {
                    val layout = resourceBrowserVerticalLayout(
                        viewportHeightDp = viewportHeight.toFloat(),
                        isAndroid = isAndroid,
                        showCaptions = showCaptions,
                    )
                    val usedHeight = layout.listHeight.value + layout.chrome.value
                    assertTrue(
                        usedHeight <= viewportHeight + 0.01f,
                        "column uses $usedHeight dp of a $viewportHeight dp window " +
                            "(isAndroid=$isAndroid, captions=$showCaptions)",
                    )
                    assertTrue(layout.listHeight.value >= 0f)
                    val available = viewportHeight - layout.chrome.value
                    if (available >= minListHeight && available <= UiTheme.Layout.modsMaxViewportHeight.value) {
                        assertEquals(
                            viewportHeight.toFloat(),
                            usedHeight,
                            0.01f,
                            "column should fill a $viewportHeight dp window (captions=$showCaptions)",
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `chrome covers the panel frame, the fixed sections and the caption band`() {
        val layout = resourceBrowserVerticalLayout(900f, isAndroid = false, showCaptions = true)

        assertEquals(
            menuPanelFrameHeight(900f).value +
                ResourceBrowserFixedContentHeight.value +
                CyberBackdropCaptionBand.value * 2f,
            layout.chrome.value,
            0.01f,
        )
        assertEquals(CyberBackdropCaptionBand, layout.captionBand)
    }

    @Test
    fun `list fills whatever is left over on a desktop window`() {
        // Regression: the old hard-coded 216 dp chrome left the column 40 dp taller than the panel.
        val viewportHeight = 895f
        val layout = resourceBrowserVerticalLayout(viewportHeight, isAndroid = false, showCaptions = true)

        assertEquals(viewportHeight, layout.listHeight.value + layout.chrome.value, 0.01f)
        assertTrue(layout.listHeight.value > UiTheme.Layout.modsMinViewportHeight.value)
    }

    @Test
    fun `captions are not reserved when the window has no room for them`() {
        val layout = resourceBrowserVerticalLayout(400f, isAndroid = true, showCaptions = false)

        assertEquals(Dp.ZERO, layout.captionBand)
    }

    @Test
    fun `unknown viewport falls back to the default list height`() {
        val layout = resourceBrowserVerticalLayout(0f, isAndroid = false, showCaptions = true)

        assertEquals(UiTheme.Layout.modsViewportHeight, layout.listHeight)
    }
}
