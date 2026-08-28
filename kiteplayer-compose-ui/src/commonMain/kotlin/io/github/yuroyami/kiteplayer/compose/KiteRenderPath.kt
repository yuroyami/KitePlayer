package io.github.yuroyami.kiteplayer.compose

/**
 * Which rendering product [KitePlayerVideo] hosts.
 *
 * [Auto] picks the platform's sustained-playback default: the native view on Android and iOS,
 * the Compose canvas on JVM desktop, which has no native video view. [NativeView] is the
 * platform-compositor path from kiteplayer-compose-interop; [ComposeCanvas] is the true Compose
 * primitive from kiteplayer-compose-video. A platform that cannot honour a request coerces it
 * and reports what actually runs through KitePlayerVideo's onEffectivePath.
 */
public enum class KiteRenderPath { Auto, NativeView, ComposeCanvas }
