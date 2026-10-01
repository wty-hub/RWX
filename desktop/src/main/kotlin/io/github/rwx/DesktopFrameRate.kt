package io.github.rwx

import com.corrodinggames.rts.gameFramework.SettingsEngine
import io.github.rwx.slick.resolveSlickTargetFrameRate

/**
 * Benchmark override for the desktop render loops. `RWX_DESKTOP_TARGET_FPS` wins over the older
 * Slick-only `RWX_SLICK_TARGET_FPS` so existing benchmark scripts keep working.
 */
internal val desktopTargetFrameRateOverride: Int? =
    (System.getenv("RWX_DESKTOP_TARGET_FPS") ?: System.getenv("RWX_SLICK_TARGET_FPS"))
        ?.toIntOrNull()
        ?.takeIf { it > 0 }

/**
 * Target frame rate for every desktop render loop, resolved exactly like the Slick canvas has always
 * resolved it (settings `maxFrameRate`, `highRefreshRate`, then the environment override).
 *
 * The Kool renderer used to rely on the swapchain as its only throttle. On macOS that is MoltenVK's
 * FIFO present mode, which caps the whole game at the display refresh rate and ignores the in-game
 * maximum frame rate setting, so the Kool renderer could never reach the frame rates the Slick path
 * showed on the same machine.
 */
internal fun desktopTargetFrameRate(
    settings: SettingsEngine = SettingsEngine.getInstance(),
    environmentOverride: Int? = desktopTargetFrameRateOverride,
): Int = resolveSlickTargetFrameRate(
    maxFrameRate = settings.maxFrameRate,
    highRefreshRate = settings.highRefreshRate,
    environmentOverride = environmentOverride,
)

/** Software pacing also honours vertical sync when the GL canvas swaps without waiting. */
internal fun desktopFramePeriodNanos(targetFrameRate: Int, vsync: Boolean, refreshRate: Int): Long {
    val fps = if (vsync) minOf(targetFrameRate, refreshRate.takeIf { it > 0 } ?: 60) else targetFrameRate
    return 1_000_000_000L / fps.coerceAtLeast(1)
}

/** Slow frames start a new interval instead of causing a burst of catch-up frames. */
internal fun desktopNextFrameDelayNanos(frameStartNanos: Long, nowNanos: Long, periodNanos: Long): Long =
    (frameStartNanos + periodNanos - nowNanos).coerceAtLeast(0L)
