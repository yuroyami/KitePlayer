package io.github.yuroyami.kiteplayer.compose

/**
 * Which rendering product [KitePlayerVideo] hosts.
 *
 * [Auto] picks the platform's sustained-playback default: the native view on Android and iOS,
 * the Compose canvas on JVM desktop. Desktop DOES have a native view since 2026-08-30 and an
 * explicit [NativeView] request is honoured there; it is not the default because it wins on jank
 * and loses on input, since Compose content painted over it cannot be clicked. [NativeView] is the
 * platform-compositor path from kiteplayer-compose-interop; [ComposeCanvas] is the true Compose
 * primitive from kiteplayer-compose-video. A platform that cannot honour a request coerces it
 * and reports what actually runs through KitePlayerVideo's onEffectivePath.
 */
public enum class KiteRenderPath { Auto, NativeView, ComposeCanvas }
