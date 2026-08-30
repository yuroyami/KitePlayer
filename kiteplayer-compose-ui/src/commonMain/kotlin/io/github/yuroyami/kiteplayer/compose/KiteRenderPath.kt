package io.github.yuroyami.kiteplayer.compose

/**
 * Which rendering product [KitePlayerVideo] hosts.
 *
 * [Auto] picks the platform's sustained-playback default, which is the native view on Android,
 * iOS and, since 2026-08-30, JVM desktop. [NativeView] is the platform-compositor path from
 * kiteplayer-compose-interop; [ComposeCanvas] is the true Compose primitive from
 * kiteplayer-compose-video. A platform that cannot honour a request coerces it and reports what
 * actually runs through KitePlayerVideo's onEffectivePath.
 *
 * **On desktop, choosing [Auto] or [NativeView] means Compose content drawn over the video cannot
 * be clicked**, because macOS routes a click to the topmost native view and painting over it does
 * not change that. Put controls that overlap the picture in a borderless window owned by the video
 * window, or ask for [ComposeCanvas], which draws the video as Compose content and takes input
 * normally at the cost of following the UI's frame rate. Controls beside the video are unaffected
 * either way.
 */
public enum class KiteRenderPath { Auto, NativeView, ComposeCanvas }
