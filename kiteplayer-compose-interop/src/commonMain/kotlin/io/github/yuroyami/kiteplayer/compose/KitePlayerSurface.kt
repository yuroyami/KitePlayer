package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlin.time.Duration

/**
 * The BASELINE Compose path: the platform player view, wrapped once.
 *
 * On Android this hosts an `io.github.yuroyami.kiteplayer.view.KitePlayerView` through
 * `AndroidView`; on iOS an `io.github.yuroyami.kiteplayer.view.KitePlayerUIView` through
 * `UIKitView`. The picture is presented by the platform's own compositor, which is why this is
 * the path for sustained fullscreen playback: the display controller shows the video while the
 * GPU idles. The default renderer adapter is installed from `kiteplayer-view-bindings`; the widget
 * itself remains backend-agnostic in `kiteplayer-view`.
 *
 * The trade is the classic interop hole: the video is not Compose content, so Compose clip,
 * alpha, rotation and shader effects do not apply to its pixels. When video must behave as a
 * true Compose primitive, use `KiteVideo` from `kiteplayer-compose-video` and read its cost note.
 *
 * The player is never owned here: opening media, playing, seeking and closing stay the
 * caller's. Passing null detaches, and this Composable leaving composition only stops the
 * picture, never the playback.
 *
 * [keepDisplayAwake] keeps the display from dimming and locking while the player plays video and
 * this surface is on screen, as the platform view's own property of that name does. True by
 * default; the desktop view accepts it and does nothing.
 *
 * [accessibilityVideoLabel] and [accessibilityStateFormat] are what a screen reader says about the
 * video, as the platform view's properties of those names. Null keeps the view's English default.
 * The desktop view has no screen reader support, so the desktop ignores both.
 */
@Composable
public fun KitePlayerSurface(
    player: KitePlayer?,
    modifier: Modifier = Modifier,
    keepDisplayAwake: Boolean = true,
    accessibilityVideoLabel: String? = null,
    accessibilityStateFormat: ((PlaybackStatus, Duration, Duration?) -> String)? = null,
) {
    platformKitePlayerSurface(player, modifier, keepDisplayAwake, accessibilityVideoLabel, accessibilityStateFormat)
}

@Composable
internal expect fun platformKitePlayerSurface(
    player: KitePlayer?,
    modifier: Modifier,
    keepDisplayAwake: Boolean,
    accessibilityVideoLabel: String?,
    accessibilityStateFormat: ((PlaybackStatus, Duration, Duration?) -> String)?,
)

/** Keeps sizing and modifier semantics intact on an explicitly unavailable placeholder target. */
@Composable
internal fun EmptyKitePlayerSurface(modifier: Modifier) {
    Layout(
        content = {},
        modifier = modifier,
    ) { _, constraints ->
        layout(constraints.minWidth, constraints.minHeight) {}
    }
}
