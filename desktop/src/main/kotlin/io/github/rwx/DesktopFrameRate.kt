package io.github.rwx

import com.corrodinggames.rts.gameFramework.SettingsEngine

/**
 * Benchmark override for the desktop render loop. `RWX_DESKTOP_TARGET_FPS` wins over the older
 * `RWX_SLICK_TARGET_FPS` so existing benchmark scripts keep working.
 */
internal val desktopTargetFrameRateOverride: Int? =
    (System.getenv("RWX_DESKTOP_TARGET_FPS") ?: System.getenv("RWX_SLICK_TARGET_FPS"))
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

/**
 * Target frame rate for the desktop render loop, resolved from the settings `maxFrameRate` /
 * `highRefreshRate` and then the environment override.
 *
 * Kool cannot rely on the swapchain as its only throttle: on macOS that is MoltenVK's FIFO present
 * mode, which caps the whole game at the display refresh rate and ignores the in-game maximum frame
 * rate setting.
 */
internal fun desktopTargetFrameRate(
    settings: SettingsEngine = SettingsEngine.getInstance(),
    environmentOverride: Int? = desktopTargetFrameRateOverride,
): Int = resolveDesktopTargetFrameRate(
    maxFrameRate = settings.maxFrameRate,
    highRefreshRate = settings.highRefreshRate,
    environmentOverride = environmentOverride,
)

internal fun resolveDesktopTargetFrameRate(
    maxFrameRate: Int,
    highRefreshRate: Boolean,
    environmentOverride: Int?,
): Int = environmentOverride
    ?: SettingsEngine.normalizeMaxFrameRate(maxFrameRate).takeIf { it > 0 }
    ?: legacyDesktopTargetFrameRate(highRefreshRate)

internal fun legacyDesktopTargetFrameRate(highRefreshRate: Boolean): Int =
    if (highRefreshRate) MAX_HIGH_REFRESH_TARGET_FPS else MAX_STANDARD_TARGET_FPS

/** Software pacing also honours vertical sync when the GL canvas swaps without waiting. */
internal fun desktopFramePeriodNanos(targetFrameRate: Int, vsync: Boolean, refreshRate: Int): Long {
    val fps = if (vsync) minOf(targetFrameRate, refreshRate.takeIf { it > 0 } ?: 60) else targetFrameRate
    return 1_000_000_000L / fps.coerceAtLeast(1)
}

/** Slow frames start a new interval instead of causing a burst of catch-up frames. */
internal fun desktopNextFrameDelayNanos(frameStartNanos: Long, nowNanos: Long, periodNanos: Long): Long =
    (frameStartNanos + periodNanos - nowNanos).coerceAtLeast(0L)

private const val MAX_STANDARD_TARGET_FPS = 120
private const val MAX_HIGH_REFRESH_TARGET_FPS = 300
