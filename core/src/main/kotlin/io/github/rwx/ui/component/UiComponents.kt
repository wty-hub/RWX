package io.github.rwx.ui.component

import io.github.rwx.ui.smallCornerRadius
import io.github.rwx.ui.mediumCornerRadius
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.scene.Node
import de.fabmax.kool.util.Color
import io.github.rwx.ui.ColorSchemeDefinition
import io.github.rwx.ui.ColorSchemeRegistry
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.UiAppearance
import io.github.rwx.ui.model.SettingsModel

enum class PanelStyle {
    Menu,
    ModWindow,
    Pause,
    Hud,
    Dialog,
    Snackbar,
}

fun Node.addPanelSurface(
    style: PanelStyle,
    name: String,
    model: SettingsModel,
    showBackdropLabels: Boolean = true,
    backgroundColor: UiScope.(ColorSchemeDefinition) -> Color? = { style.backgroundColor(it) },
    themeBackgroundColor: UiScope.(ColorSchemeDefinition) -> Color = { style.backgroundColor(it) },
    block: UiScope.(ColorSchemeDefinition) -> Unit,
): UiSurface {
    val initialScheme = visualScheme(style, model.selectedColorSchemeId.value)
    return addPanelSurface(
        name = name,
        colors = UiTheme.colors(initialScheme, style.backgroundColor(initialScheme)),
        sizes = UiTheme.sizes,
        backgroundColor = {
            val scheme = applySelectedColorScheme(model, style, themeBackgroundColor)
            backgroundColor(scheme)
        },
        layout = if (style.fillsSurface()) CellLayout else ColumnLayout,
        width = if (style.fillsSurface()) Grow.Std else FitContent,
        height = if (style.fillsSurface()) Grow.Std else FitContent,
    ) {
        val theme = applySelectedColorScheme(model, style, themeBackgroundColor)
        val isShortViewport = usesShortViewportLayout(style)
        if (style.fillsSurface()) {
            Box(width = Grow.Std, height = Grow.Std) {
                modifier
                    .align(style.alignmentX(), style.alignmentY())
                    .margin(style.outerMargin())
                    .padding(style.innerPadding(isShortViewport))
                if (theme.appearance == UiAppearance.Cyberpunk) CyberBackdrop(theme, showBackdropLabels)
                Column(width = FitContent, height = FitContent) {
                    modifier.align(AlignmentX.Center, style.contentAlignmentY(isShortViewport))
                    if (style == PanelStyle.Dialog) {
                        modifier
                            .padding(UiTheme.Spacing.xl)
                            .background(RoundRectBackground(theme.palette.surfaceBase, theme.mediumCornerRadius))
                            .border(RoundRectBorder(theme.palette.borderSubtle, theme.mediumCornerRadius, Dp(1f)))
                    }
                    block(theme)
                }
            }
        } else {
            modifier
                .align(style.alignmentX(), style.alignmentY())
                .margin(style.outerMargin())
                .padding(style.innerPadding(isShortViewport))
            block(theme)
        }
    }
}

private fun UiScope.usesShortViewportLayout(style: PanelStyle): Boolean {
    if (style != PanelStyle.Menu && style != PanelStyle.ModWindow && style != PanelStyle.Pause) {
        return false
    }
    return isShortViewport(Dp.fromPx(surface.viewportHeight.use()).value)
}

/**
 * Vertical space a [PanelStyle.Menu] panel spends on its own frame: the outer margin plus the inner
 * padding that surrounds the content column. Hosts subtract it (together with the fixed sections
 * above and below their list) when they size a list viewport, so the content column never spills
 * over the frame.
 */
internal fun menuPanelFrameHeight(viewportHeightDp: Float): Dp {
    val style = PanelStyle.Menu
    val padding = style.innerPadding(isShortViewport(viewportHeightDp))
    return Dp(style.outerMargin().value * 2f + padding.value * 2f)
}

internal fun isShortViewport(viewportHeightDp: Float): Boolean =
    viewportHeightDp in 1f..<SHORT_VIEWPORT_HEIGHT_DP

private fun UiScope.applySelectedColorScheme(
    model: SettingsModel,
    style: PanelStyle,
    backgroundColor: UiScope.(ColorSchemeDefinition) -> Color,
): ColorSchemeDefinition {
    val scheme = visualScheme(style, model.selectedColorSchemeId.use())
    surface.colors = UiTheme.colors(scheme, backgroundColor(scheme))
    return scheme
}

internal fun visualScheme(style: PanelStyle, id: io.github.rwx.ui.ColorSchemeId): ColorSchemeDefinition =
    when (style) {
        PanelStyle.Menu, PanelStyle.Pause, PanelStyle.Dialog, PanelStyle.Snackbar -> ColorSchemeRegistry.cyberpunkMenuScheme
        else -> ColorSchemeRegistry.schemeFor(id)
    }

fun UiScope.ScreenTitle(
    text: String,
    theme: ColorSchemeDefinition,
    width: Dp = UiTheme.Layout.menuButtonWidth,
) {
    Text(text) {
        modifier
            .align(AlignmentX.Center, AlignmentY.Top)
            .width(width)
            .margin(bottom = UiTheme.Spacing.lg)
            .font(UiTheme.Fonts.headingLarge)
            .textAlign(AlignmentX.Center, AlignmentY.Center)
            .textColor(if (theme.appearance == UiAppearance.Cyberpunk) theme.palette.primary else theme.palette.textPrimary)
    }
    if (theme.appearance == UiAppearance.Cyberpunk) {
        Box(width = Dp(82f), height = Dp(2f)) {
            modifier.alignX(AlignmentX.Center)
                .margin(bottom = UiTheme.Spacing.md)
                .backgroundColor(theme.palette.secondary)
        }
    }
}

fun UiScope.SectionTitle(
    text: String,
    theme: ColorSchemeDefinition,
    width: Dp = UiTheme.Layout.settingsRowWidth,
) {
    Text(text) {
        modifier
            .width(width)
            .margin(top = UiTheme.Spacing.sm, bottom = UiTheme.Spacing.sm)
            .font(UiTheme.Fonts.headingSmall)
            .textColor(theme.palette.textPrimary)
    }
}

fun UiScope.BodyText(
    text: String,
    theme: ColorSchemeDefinition,
    width: Dp = UiTheme.Layout.menuButtonWidth,
) {
    Text(text) {
        modifier
            .width(width)
            .margin(all = UiTheme.Spacing.xs)
            .font(UiTheme.Fonts.bodySmall)
            .textAlign(AlignmentX.Center, AlignmentY.Center)
            .isWrapText(true)
            .textColor(theme.palette.textSecondary)
    }
}

fun UiScope.BackButton(
    label: String,
    width: Dp,
    theme: ColorSchemeDefinition,
    onPressed: () -> Unit,
) {
    TextIconButton(label, Icon.Back, width, theme) { onPressed() }
}

private fun PanelStyle.backgroundColor(theme: ColorSchemeDefinition) = when (this) {
    PanelStyle.Menu -> theme.palette.panelOverlayLight
    PanelStyle.ModWindow -> theme.palette.panelOverlayLight
    PanelStyle.Pause -> theme.palette.panelOverlayDark
    PanelStyle.Hud -> theme.palette.panelHud
    PanelStyle.Dialog -> theme.palette.panelOverlayDark
    PanelStyle.Snackbar -> Color("00000000")
}

private fun PanelStyle.fillsSurface() = when (this) {
    PanelStyle.Menu,
    PanelStyle.ModWindow,
    PanelStyle.Pause,
    PanelStyle.Dialog,
    PanelStyle.Snackbar -> true

    PanelStyle.Hud -> false
}

private fun PanelStyle.alignmentX() = when (this) {
    PanelStyle.Hud -> AlignmentX.Start
    PanelStyle.Menu,
    PanelStyle.ModWindow,
    PanelStyle.Pause,
    PanelStyle.Dialog,
    PanelStyle.Snackbar -> AlignmentX.Center
}

private fun PanelStyle.alignmentY() = when (this) {
    PanelStyle.Hud -> AlignmentY.Top
    PanelStyle.Snackbar -> AlignmentY.Bottom
    PanelStyle.Menu,
    PanelStyle.ModWindow,
    PanelStyle.Pause,
    PanelStyle.Dialog -> AlignmentY.Center
}

private fun PanelStyle.contentAlignmentY(isShortViewport: Boolean) = when (this) {
    PanelStyle.Menu,
    PanelStyle.ModWindow,
    PanelStyle.Pause -> if (isShortViewport) AlignmentY.Top else AlignmentY.Center

    PanelStyle.Hud,
    PanelStyle.Snackbar,
    PanelStyle.Dialog -> alignmentY()
}

private fun PanelStyle.outerMargin() = when (this) {
    PanelStyle.Hud -> UiTheme.Spacing.md
    PanelStyle.Dialog, PanelStyle.Snackbar -> Dp.ZERO
    PanelStyle.Menu, PanelStyle.ModWindow, PanelStyle.Pause -> UiTheme.Spacing.xs
}

private fun PanelStyle.innerPadding(isShortViewport: Boolean) = when (this) {
    PanelStyle.Hud -> UiTheme.Spacing.sm
    PanelStyle.Dialog, PanelStyle.Snackbar -> Dp.ZERO
    PanelStyle.Menu, PanelStyle.ModWindow, PanelStyle.Pause -> if (isShortViewport) UiTheme.Spacing.sm else UiTheme.Spacing.xl
}

private const val SHORT_VIEWPORT_HEIGHT_DP: Float = 520f
fun UiScope.TextFieldWithTrailingIcon(
    value: String,
    hint: String,
    theme: ColorSchemeDefinition,
    contentWidth: Dimension = UiTheme.Layout.dialogMessageWidth,
    compact: Boolean = false,
    trailingIcon: Icon? = null,
    trailingIconTooltip: String? = null,
    onTrailingIconPress: (() -> Unit)? = null,
    onEnter: ((String) -> Unit)? = null,
    focusOnShow: Boolean = false,
    inputSession: Int = 0,
    onChange: (String) -> Unit,
) {
    val inputHeight = if (compact) UiTheme.Layout.CompactMenuButtonHeight else UiTheme.Layout.menuButtonHeight
    Box(width = contentWidth, height = inputHeight) {
        modifier.margin(bottom = if (compact) Dp.ZERO else UiTheme.Spacing.lg)
        RwxTextField(value, autoFocusKey = if (focusOnShow) inputSession else null) {
            modifier
                .width(Grow.Std)
                .height(Grow.Std)
                .padding(
                    start = UiTheme.Spacing.sm,
                    end = if (trailingIcon == null) Dp.ZERO else inputHeight,
                )
                .hint(hint)
                .font(UiTheme.Fonts.bodySmall)
                .colors(
                    textColor = theme.palette.textPrimary,
                    hintColor = theme.palette.textSecondary,
                    lineColor = theme.palette.borderSubtle,
                    lineColorFocused = theme.palette.primary,
                    cursorColor = theme.palette.primary,
                    selectionColor = theme.palette.primaryContainer,
                )
                .onChange(onChange)
                .onEnterPressed(onEnter)
        }
        trailingIcon?.let { icon ->
            val iconSize = if (compact) Dp(18f) else UiTheme.Layout.textButtonGlyphSize
            if (onTrailingIconPress != null) {
                IconButton(
                    icon = icon,
                    theme = theme,
                    size = inputHeight,
                    iconSize = iconSize,
                    tooltip = trailingIconTooltip,
                    onPressed = onTrailingIconPress,
                ).modifier.align(AlignmentX.End, AlignmentY.Center)
            } else {
                Box(width = inputHeight, height = Grow.Std) {
                    modifier.align(AlignmentX.End, AlignmentY.Center)
                    Icon(icon, iconSize, theme.palette.primary).modifier
                        .align(AlignmentX.Center, AlignmentY.Center)
                }
            }
        }
    }
}
