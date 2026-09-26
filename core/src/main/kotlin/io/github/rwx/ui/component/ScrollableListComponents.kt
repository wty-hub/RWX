package io.github.rwx.ui.component

import de.fabmax.kool.modules.ui2.*
import io.github.rwx.ui.ColorSchemeDefinition
import io.github.rwx.ui.UiTheme


/**
 * Full (non-virtual) column that pins to the latest item. Chat lines wrap to different heights;
 * Kool's [LazyColumn] `scrollToItem(last)` then parks the last line at the top and never
 * composes the messages above it, so they look missing.
 */
fun <T> UiScope.StickToEndScrollColumn(
    items: List<T>,
    theme: ColorSchemeDefinition,
    width: Dimension = Grow.Std,
    height: Dimension = Grow.Std,
    itemContent: UiScope.(T) -> Unit,
) {
    val scrollState = rememberScrollState()
    val follow = remember(ListEndFollow())
    val contentHeight = scrollState.contentHeightDp.use()
    val viewHeight = scrollState.viewHeightDp.use()
    val yScroll = scrollState.yScrollDp.use()
    val laidOut = viewHeight > 0f && contentHeight > 0f
    val remaining = contentHeight - (yScroll + viewHeight)
    val decision = nextListEndFollow(
        follow = follow.value,
        itemCount = items.size,
        laidOut = laidOut,
        atEnd = !laidOut || remaining <= STICK_TO_END_SLACK_DP,
    )
    follow.value = decision.follow
    if (decision.scrollToIndex != null) {
        scrollState.scrollRelativeY(1f, smooth = false)
    }
    ScrollArea(
        width = width,
        height = height,
        withVerticalScrollbar = true,
        withHorizontalScrollbar = false,
        isScrollableVertical = true,
        isScrollableHorizontal = false,
        scrollbarColor = theme.palette.primary,
        state = scrollState,
        containerModifier = {
            it
                .backgroundColor(null)
                .onDrag { event ->
                    scrollState.scrollDpY(Dp.fromPx(-event.pointer.delta.y).value)
                }
        },
    ) {
        // ScrollPane defaults to FitContent, so an unwrapped line widens the pane and
        // the text is clipped at the viewport edge. Grow keeps every line inside the
        // viewport (so isWrapText has a real width). End padding clears the overlay bar.
        modifier
            .width(Grow.Std)
            .height(FitContent)
            .padding(end = SCROLLBAR_LANE_DP)
        Column(width = Grow.Std) {
            items.forEach { item ->
                itemContent(item)
            }
        }
    }
}

fun <T> UiScope.ScrollableVerticalList(
    items: List<T>,
    theme: ColorSchemeDefinition,
    width: Dimension = UiTheme.Layout.levelSelectButtonWidth,
    height: Dimension = UiTheme.Layout.scrollViewportHeight,
    isScrollByDrag: Boolean = true,
    framed: Boolean = false,
    contentPadding: Dp = Dp.ZERO,
    bottomContentPadding: Dp = Dp.ZERO,
    stickToEnd: Boolean = false,
    itemContent: UiScope.(T) -> Unit,
) {
    // Always allocate the same remember slots so callers that branch around this
    // list (or toggle stickToEnd) do not corrupt sibling remember state.
    val listState = rememberListState()
    val follow = remember(ListEndFollow())
    if (stickToEnd) {
        followListEnd(listState, follow, items.size)
    }
    if (framed) {
        Box(width = width, height = height) {
            modifier
                .background(RoundRectBackground(theme.palette.surfaceSunken, UiTheme.Spacing.sm))
                .border(RoundRectBorder(theme.palette.borderSubtle, UiTheme.Spacing.sm, Dp(1f)))
                .padding(contentPadding)

            LazyColumn(
                items = items,
                theme = theme,
                width = Grow.Std,
                height = Grow.Std,
                isScrollByDrag = isScrollByDrag,
                bottomContentPadding = bottomContentPadding,
                state = listState,
                itemContent = itemContent,
            )
        }
    } else {
        LazyColumn(
            items = items,
            theme = theme,
            width = width,
            height = height,
            isScrollByDrag = isScrollByDrag,
            bottomContentPadding = bottomContentPadding,
            state = listState,
            itemContent = itemContent,
        )
    }
}

private fun UiScope.followListEnd(
    listState: LazyListState,
    follow: MutableStateValue<ListEndFollow>,
    itemCount: Int,
) {
    val from = listState.itemsFrom.use()
    val laidOut = listState.numVisibleItems > 0 && listState.numTotalItems > 0
    val atEnd = laidOut && from + listState.numVisibleItems >= listState.numTotalItems
    val decision = nextListEndFollow(
        follow = follow.value,
        itemCount = itemCount,
        laidOut = laidOut,
        atEnd = atEnd,
    )
    follow.value = decision.follow
    decision.scrollToIndex?.let { listState.scrollToItem.set(it) }
}

private fun <T> UiScope.LazyColumn(
    items: List<T>,
    theme: ColorSchemeDefinition,
    width: Dimension,
    height: Dimension,
    isScrollByDrag: Boolean,
    bottomContentPadding: Dp,
    state: LazyListState,
    itemContent: UiScope.(T) -> Unit,
) {
    LazyColumn(
        width = width,
        height = height,
        withVerticalScrollbar = true,
        withHorizontalScrollbar = false,
        isScrollableHorizontal = false,
        isScrollByDrag = isScrollByDrag,
        state = state,
        scrollbarColor = theme.palette.primary,
        containerModifier = { it.backgroundColor(null) },
        scrollPaneModifier = { it.allowOverScroll(x = false, y = false) },
    ) {
        val hasBottomPadding = bottomContentPadding.value > 0f
        indices(items.size + if (hasBottomPadding) 1 else 0) { index ->
            if (index < items.size) {
                itemContent(items[index])
            } else {
                Box(width = Grow.Std, height = bottomContentPadding) { }
            }
        }
    }
}

internal data class ListEndFollow(
    val itemCount: Int = 0,
    val pinnedToEnd: Boolean = true,
    val reachedEnd: Boolean = false,
)

internal data class ListEndFollowDecision(
    val follow: ListEndFollow,
    val scrollToIndex: Int?,
)

internal fun nextListEndFollow(
    follow: ListEndFollow,
    itemCount: Int,
    laidOut: Boolean,
    atEnd: Boolean,
): ListEndFollowDecision {
    if (itemCount <= 0) {
        return ListEndFollowDecision(
            follow = ListEndFollow(),
            scrollToIndex = null,
        )
    }
    val scrollToEnd = !laidOut || !follow.reachedEnd || (itemCount > follow.itemCount && follow.pinnedToEnd)
    val pinnedToEnd = when {
        !laidOut || scrollToEnd -> true
        else -> atEnd
    }
    return ListEndFollowDecision(
        follow = ListEndFollow(
            itemCount = itemCount,
            pinnedToEnd = pinnedToEnd,
            reachedEnd = follow.reachedEnd || (laidOut && atEnd),
        ),
        scrollToIndex = if (scrollToEnd) itemCount - 1 else null,
    )
}

private const val STICK_TO_END_SLACK_DP: Float = 8f

/** Matches Kool's default vertical scrollbar width so wrapped glyphs stay left of the bar. */
private val SCROLLBAR_LANE_DP: Dp = Dp(8f)
