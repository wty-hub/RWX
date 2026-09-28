package io.github.rwx.ui.host

import io.github.rwx.ui.smallCornerRadius
import io.github.rwx.ui.mediumCornerRadius
import de.fabmax.kool.modules.ui2.*
import de.fabmax.kool.scene.Scene
import de.fabmax.kool.util.Color
import io.github.rwx.ui.UiTheme
import io.github.rwx.ui.component.*
import io.github.rwx.ui.model.*

class MainMenuSceneHost(
    private val model: SettingsModel = SettingsModel(),
    private val onAction: (MainMenuAction) -> Unit = {},
) {
    var items: List<MainMenuItem> = emptyList()
        private set

    private val menuItems = mutableStateListOf<MainMenuItem>()
    private val battleBackgroundVisible = mutableStateOf(false)

    fun updateItems(conditions: MainMenuConditions) {
        val next = MainMenuViewModel.items(conditions)
        if (next != items) {
            items = next
            menuItems.atomic {
                clear()
                addAll(next)
            }
        }
    }

    /** Dispatches a menu action to the handler. Kept separate so click routing is testable. */
    fun dispatch(action: MainMenuAction) {
        onAction(action)
    }

    fun setBattleBackgroundVisible(visible: Boolean) {
        battleBackgroundVisible.value = visible
    }

    fun createScene(): Scene = UiScene(MAIN_MENU_SCENE_NAME) {
        addPanelSurface(
            PanelStyle.Menu,
            "main-menu-panel",
            model,
            backgroundColor = { theme ->
                if (battleBackgroundVisible.use()) {
                    TRANSPARENT_BACKGROUND
                } else {
                    theme.palette.panelOverlayLight.withAlpha(MAIN_MENU_SURFACE_ALPHA)
                }
            },
            themeBackgroundColor = { theme ->
                theme.palette.panelOverlayLight.withAlpha(MAIN_MENU_SURFACE_ALPHA)
            },
        ) { theme ->
            MainMenuLauncher(menuItems.use(), theme, ::dispatch)
        }
    }

    companion object {
        const val MAIN_MENU_SCENE_NAME: String = "main-menu"
        const val MENU_TITLE: String = "RWXX"
        const val MENU_SUBTITLE: String = "Rusted Warfare Extension"
    }
}

private val TRANSPARENT_BACKGROUND = Color("00000000")

private const val MAIN_MENU_SURFACE_ALPHA: Float = 0.9f
private const val MAIN_MENU_PANEL_START_ALPHA: Float = 0.94f
private const val MAIN_MENU_PANEL_END_ALPHA: Float = 0.82f
private const val MAIN_MENU_HORIZONTAL_MARGIN_DP: Float = 48f
private val MAIN_MENU_FOOTER_BUTTON_WIDTH: Dp = Dp(220f)
private val MAIN_MENU_FOOTER_HEIGHT: Dp = Dp(64f)
private val MAIN_MENU_GRID_GAP: Dp = Dp(10f)

private data class MainMenuLayoutMetrics(
    val contentWidth: Dp,
    val menuViewportHeight: Dp,
    val columns: Int,
    val cardHeight: Dp,
    val isShortLandscape: Boolean,
)

private fun UiScope.MainMenuLauncher(
    items: List<MainMenuItem>,
    theme: io.github.rwx.ui.ColorSchemeDefinition,
    onAction: (MainMenuAction) -> Unit,
) {
    val metrics = mainMenuLayoutMetrics()
    val primaryItems = items.filterNot { it.action == MainMenuAction.About || it.action == MainMenuAction.Exit }
    val footerItems = items.filter { it.action == MainMenuAction.About || it.action == MainMenuAction.Exit }

    MainMenuHeader(metrics.contentWidth, theme, metrics.isShortLandscape)
    MainMenuGrid(
        items = primaryItems,
        theme = theme,
        width = metrics.contentWidth,
        height = metrics.menuViewportHeight,
        columns = metrics.columns,
        cardHeight = metrics.cardHeight,
        onAction = onAction,
    )
    MainMenuFooter(footerItems, theme, metrics.contentWidth, onAction)
}

private fun UiScope.MainMenuFooter(
    items: List<MainMenuItem>,
    theme: io.github.rwx.ui.ColorSchemeDefinition,
    width: Dp,
    onAction: (MainMenuAction) -> Unit,
) {
    val buttonWidth = if (width.value < 500f) Dp((width.value - 8f) / 2f) else MAIN_MENU_FOOTER_BUTTON_WIDTH
    Box(width = width, height = MAIN_MENU_FOOTER_HEIGHT) {
        modifier.margin(top = UiTheme.Spacing.xs)
        Row(width = FitContent, height = UiTheme.Layout.menuButtonHeight) {
            modifier.align(AlignmentX.Center, AlignmentY.Center)
            items.forEach { item ->
                TextIconButton(
                    label = item.label,
                    icon = item.action.menuIcon,
                    width = buttonWidth,
                    theme = theme,
                ) {
                    onAction(item.action)
                }
            }
        }
    }
}

private fun UiScope.MainMenuGrid(
    items: List<MainMenuItem>,
    theme: io.github.rwx.ui.ColorSchemeDefinition,
    width: Dp,
    height: Dp,
    columns: Int,
    cardHeight: Dp,
    onAction: (MainMenuAction) -> Unit,
) {
    val innerWidth = Dp(width.value - 2f * UiTheme.Spacing.sm.value)
    val cardWidth = Dp((innerWidth.value - MAIN_MENU_GRID_GAP.value * (columns - 1)) / columns)
    Box(width = width, height = height) {
        modifier
            .alignX(AlignmentX.Center)
            .margin(top = UiTheme.Spacing.xs, bottom = UiTheme.Spacing.sm)
            .background(
                LinearGradientBackground(
                    theme.palette.surfaceBase.withAlpha(MAIN_MENU_PANEL_START_ALPHA),
                    theme.palette.surfaceRaised.withAlpha(MAIN_MENU_PANEL_END_ALPHA),
                    cornerRadius = theme.mediumCornerRadius,
                )
            )
            .border(RoundRectBorder(theme.palette.borderSubtle, theme.mediumCornerRadius, Dp(1f)))
            .padding(horizontal = UiTheme.Spacing.sm, vertical = UiTheme.Spacing.sm)

        MainMenuGridLines(theme, innerWidth, height)
        ScrollArea(
            width = Grow.Std,
            height = Grow.Std,
            withVerticalScrollbar = true,
            withHorizontalScrollbar = false,
            isScrollableVertical = true,
            isScrollableHorizontal = false,
            scrollbarColor = theme.palette.primary,
        ) {
            modifier.width(Grow.Std).height(FitContent)
            Column(width = Grow.Std, height = FitContent) {
                items.chunked(columns).forEach { rowItems ->
                    Row(width = Grow.Std, height = cardHeight) {
                        rowItems.forEachIndexed { index, item ->
                            Box(width = cardWidth, height = cardHeight) {
                                if (index > 0) modifier.margin(start = MAIN_MENU_GRID_GAP)
                                mainMenuTileCard(item, theme) { onAction(item.action) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun UiScope.MainMenuGridLines(
    theme: io.github.rwx.ui.ColorSchemeDefinition,
    width: Dp,
    height: Dp,
) {
    val gridColor = theme.palette.primary.withAlpha(0.055f)
    repeat((height.value / 48f).toInt()) { index ->
        Box(width = Grow.Std, height = Dp(1f)) {
            modifier.alignY(AlignmentY.Top).margin(top = Dp((index + 1) * 48f)).backgroundColor(gridColor)
        }
    }
    repeat((width.value / 112f).toInt()) { index ->
        Box(width = Dp(1f), height = Grow.Std) {
            modifier.alignX(AlignmentX.Start).margin(start = Dp((index + 1) * 112f)).backgroundColor(gridColor)
        }
    }
}

private fun UiScope.mainMenuTileCard(
    item: MainMenuItem,
    theme: io.github.rwx.ui.ColorSchemeDefinition,
    onPressed: () -> Unit,
) {
    val hovered = remember(false)
    val isHovered = hovered.use()
    val background = if (isHovered) theme.palette.surfaceRaised else theme.palette.surfaceSunken
    val border = if (isHovered) theme.palette.primary else theme.palette.borderSubtle
    val iconColor = if (isHovered) theme.palette.secondary else theme.palette.primary
    val textColor = if (isHovered) theme.palette.primary else theme.palette.textPrimary

    Box(width = Grow.Std, height = Grow.Std) {
        modifier
            .margin(UiTheme.Spacing.xs)
            .background(RoundRectBackground(background, theme.smallCornerRadius))
            .border(RoundRectBorder(border, theme.smallCornerRadius, Dp(1f)))
            .onEnter { hovered.value = true }
            .onExit { hovered.value = false }
            .onClick { onPressed() }

        Box(width = Grow.Std, height = Dp(2f)) {
            modifier.alignY(AlignmentY.Bottom).backgroundColor(if (isHovered) theme.palette.secondary else theme.palette.primary)
        }
        Column(width = Grow.Std, height = Grow.Std) {
            modifier
                .align(AlignmentX.Center, AlignmentY.Center)
                .padding(horizontal = UiTheme.Spacing.md, vertical = UiTheme.Spacing.sm)
            Text(item.badge) {
                modifier
                    .width(Grow.Std)
                    .font(UiTheme.Fonts.caption)
                    .textAlign(AlignmentX.End, AlignmentY.Center)
                    .textColor(theme.palette.secondary)
            }
            Box(width = Grow.Std, height = Dp(48f)) {
                Icon(item.action.menuIcon, UiTheme.Layout.mainMenuTileIconSize, iconColor).modifier
                    .align(AlignmentX.Start, AlignmentY.Bottom)
            }
            Text(item.label) {
                modifier
                    .width(Grow.Std)
                    .height(Grow.Std)
                    .font(UiTheme.Fonts.bodySmall)
                    .textAlign(AlignmentX.Start, AlignmentY.Center)
                    .isWrapText(true)
                    .clipToBounds(true)
                    .textColor(textColor)
            }
        }
    }
}

internal fun UiScope.MainMenuHeader(
    contentWidth: Dp,
    theme: io.github.rwx.ui.ColorSchemeDefinition,
    isShortLandscape: Boolean = false,
) {
    GradientMainTitle(MainMenuSceneHost.MENU_TITLE, contentWidth, theme)
    Text(MainMenuSceneHost.MENU_SUBTITLE) {
        modifier
            .width(contentWidth)
            .height(Dp(32f))
            .margin(bottom = if (isShortLandscape) UiTheme.Spacing.sm else UiTheme.Spacing.xl)
            .font(UiTheme.Fonts.bodySmall)
            .textAlign(AlignmentX.Center, AlignmentY.Center)
            .isWrapText(false)
            .clipToBounds(true)
            .textColor(theme.palette.textSecondary)
    }
}

internal fun UiScope.GradientMainTitle(
    text: String,
    contentWidth: Dp,
    theme: io.github.rwx.ui.ColorSchemeDefinition,
) {
    Box(width = contentWidth, height = UiTheme.Layout.mainMenuTitleHeight) {
        modifier
            .width(contentWidth)
            .margin(bottom = UiTheme.Spacing.xs)

        if (UiTheme.Fonts.titleInstalledState().use()) {
            GradientText(text) {
                modifier
                    .height(Grow.Std)
                    .align(AlignmentX.Center, AlignmentY.Center)
                    .font(if (contentWidth.value < 400f) UiTheme.Fonts.titleBase.derive(60f) else UiTheme.Fonts.displayTitle)
                    .textAlign(AlignmentX.Center, AlignmentY.Center)
                    .gradientColors(theme.palette.secondary, theme.palette.primary)
            }
        }
    }
}

private fun UiScope.mainMenuLayoutMetrics(): MainMenuLayoutMetrics {
    val viewportWidthDp = Dp.fromPx(surface.viewportWidth.use()).value
    val viewportHeightDp = Dp.fromPx(surface.viewportHeight.use()).value
    val isShortLandscape = viewportWidthDp > viewportHeightDp &&
            viewportHeightDp in 1f..<MAIN_MENU_SHORT_LANDSCAPE_HEIGHT_DP
    val contentWidth = if (viewportWidthDp > 0f) {
        Dp(
            (viewportWidthDp - MAIN_MENU_HORIZONTAL_MARGIN_DP)
                .coerceAtLeast(0f)
                .coerceAtMost(UiTheme.Layout.mainMenuMaxContentWidth.value)
        )
    } else {
        UiTheme.Layout.mainMenuContentWidth
    }
    val columns = when {
        contentWidth.value >= 980f -> 4
        contentWidth.value >= 540f -> 2
        else -> 1
    }
    val cardHeight = if (isShortLandscape) Dp(108f) else UiTheme.Layout.mainMenuTileHeight
    val reservedHeight = if (isShortLandscape) 230f else 290f
    val viewportHeight = Dp((viewportHeightDp - reservedHeight).coerceIn(100f, 470f))
    return MainMenuLayoutMetrics(
        contentWidth = contentWidth,
        menuViewportHeight = viewportHeight,
        columns = columns,
        cardHeight = cardHeight,
        isShortLandscape = isShortLandscape,
    )
}

private const val MAIN_MENU_SHORT_LANDSCAPE_HEIGHT_DP: Float = 520f

private val MainMenuAction.menuIcon: Icon
    get() = when (this) {
        MainMenuAction.Continue -> Icon.Continue
        MainMenuAction.SinglePlayer -> Icon.Map
        MainMenuAction.WatchReplay -> Icon.Replay
        MainMenuAction.Sandbox -> Icon.Sandbox
        MainMenuAction.Multiplayer,
        MainMenuAction.P2PMultiplayer -> Icon.Multiplayer

        MainMenuAction.Settings -> Icon.Settings
        MainMenuAction.Mods -> Icon.Mods
        MainMenuAction.ResourceBrowser -> Icon.Search
        MainMenuAction.About -> Icon.Help
        MainMenuAction.Exit -> Icon.Exit
    }
