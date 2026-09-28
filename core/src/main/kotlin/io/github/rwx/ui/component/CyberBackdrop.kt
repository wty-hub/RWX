package io.github.rwx.ui.component

import de.fabmax.kool.modules.ui2.*
import io.github.rwx.ui.ColorSchemeDefinition
import io.github.rwx.ui.UiAppearance
import io.github.rwx.ui.UiTheme

/** Quiet decorative layer behind menu content. Every element is non-interactive. */
fun UiScope.CyberBackdrop(theme: ColorSchemeDefinition, showLabels: Boolean = true) {
    val width = Dp.fromPx(surface.viewportWidth.use()).value
    val height = Dp.fromPx(surface.viewportHeight.use()).value
    val gridColor = theme.palette.primary.withAlpha(0.045f)
    val edgeColor = theme.palette.primary.withAlpha(0.68f)
    val ghostText = theme.palette.textSecondary.withAlpha(0.52f)

    repeat((width / 72f).toInt().coerceAtMost(32)) { index ->
        Box(width = Dp(1f), height = Grow.Std) {
            modifier.alignX(AlignmentX.Start)
                .margin(start = Dp((index + 1) * 72f))
                .backgroundColor(gridColor)
        }
    }
    repeat((height / 72f).toInt().coerceAtMost(20)) { index ->
        Box(width = Grow.Std, height = Dp(1f)) {
            modifier.alignY(AlignmentY.Top)
                .margin(top = Dp((index + 1) * 72f))
                .backgroundColor(gridColor)
        }
    }

    // Corner brackets and segmented status rails make the scene feel like one instrument panel.
    Box(width = Dp(46f), height = Dp(2f)) {
        modifier.align(AlignmentX.Start, AlignmentY.Top)
            .margin(start = Dp(20f), top = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(2f), height = Dp(34f)) {
        modifier.align(AlignmentX.Start, AlignmentY.Top)
            .margin(start = Dp(20f), top = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(46f), height = Dp(2f)) {
        modifier.align(AlignmentX.End, AlignmentY.Top)
            .margin(end = Dp(20f), top = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(2f), height = Dp(34f)) {
        modifier.align(AlignmentX.End, AlignmentY.Top)
            .margin(end = Dp(20f), top = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(46f), height = Dp(2f)) {
        modifier.align(AlignmentX.Start, AlignmentY.Bottom)
            .margin(start = Dp(20f), bottom = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(2f), height = Dp(34f)) {
        modifier.align(AlignmentX.Start, AlignmentY.Bottom)
            .margin(start = Dp(20f), bottom = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(46f), height = Dp(2f)) {
        modifier.align(AlignmentX.End, AlignmentY.Bottom)
            .margin(end = Dp(20f), bottom = Dp(20f)).backgroundColor(edgeColor)
    }
    Box(width = Dp(2f), height = Dp(34f)) {
        modifier.align(AlignmentX.End, AlignmentY.Bottom)
            .margin(end = Dp(20f), bottom = Dp(20f)).backgroundColor(edgeColor)
    }

    if (showLabels && width >= 650f && height >= 480f) {
        Text("RWXX  //  TACTICAL INTERFACE") {
            modifier.align(AlignmentX.Start, AlignmentY.Top)
                .margin(start = Dp(78f), top = Dp(15f))
                .font(UiTheme.Fonts.caption).textColor(ghostText)
        }
        Text("SYSTEM  /  01") {
            modifier.align(AlignmentX.End, AlignmentY.Top)
                .margin(end = Dp(78f), top = Dp(15f))
                .font(UiTheme.Fonts.caption).textColor(ghostText)
        }
        Text("RUSTED WARFARE EXTENSION") {
            modifier.align(AlignmentX.Start, AlignmentY.Bottom)
                .margin(start = Dp(78f), bottom = Dp(13f))
                .font(UiTheme.Fonts.caption).textColor(ghostText)
        }
        Row(width = FitContent, height = Dp(4f)) {
            modifier.align(AlignmentX.End, AlignmentY.Bottom)
                .margin(end = Dp(80f), bottom = Dp(22f))
            repeat(8) { index ->
                Box(width = Dp(if (index % 3 == 0) 20f else 9f), height = Dp(2f)) {
                    modifier.margin(start = Dp(3f))
                        .backgroundColor(if (index == 7) theme.palette.secondary.withAlpha(0.7f) else edgeColor)
                }
            }
        }
    }
}

/** Small frame details for cards and image windows; classic controls render no extra chrome. */
fun UiScope.CyberCardRail(theme: ColorSchemeDefinition, active: Boolean = false) {
    if (theme.appearance != UiAppearance.Cyberpunk) return
    Box(width = Grow.Std, height = Dp(1f)) {
        modifier.alignY(AlignmentY.Bottom)
            .backgroundColor(theme.palette.primary.withAlpha(if (active) 0.9f else 0.32f))
    }
    Box(width = Dp(32f), height = Dp(2f)) {
        modifier.align(AlignmentX.End, AlignmentY.Top)
            .backgroundColor(theme.palette.secondary.withAlpha(if (active) 0.95f else 0.58f))
    }
    Box(width = Dp(2f), height = Dp(18f)) {
        modifier.align(AlignmentX.End, AlignmentY.Top)
            .backgroundColor(theme.palette.secondary.withAlpha(if (active) 0.95f else 0.58f))
    }
}
