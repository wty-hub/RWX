package io.github.rwx.ui

import de.fabmax.kool.modules.ui2.Dp

/** Shape tokens shared by menu components without changing classic in-game controls. */
val ColorSchemeDefinition.smallCornerRadius: Dp
    get() = if (appearance == UiAppearance.Cyberpunk) Dp(2f) else UiTheme.Spacing.xs

val ColorSchemeDefinition.mediumCornerRadius: Dp
    get() = if (appearance == UiAppearance.Cyberpunk) Dp(3f) else UiTheme.Spacing.sm
