package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import io.github.yuroyami.kiteplayer.KitePlayer

/**
 * The BASELINE Compose path (D-6): the platform player view, wrapped once.
 *
 * On Android this hosts an `io.github.yuroyami.kiteplayer.view.KitePlayerView` through
 * `AndroidView`; on iOS an `io.github.yuroyami.kiteplayer.view.KitePlayerUIView` through
 * `UIKitView`. The picture is presented by the platform's own compositor, which is why this is
 * the path for sustained fullscreen playback: the display controller shows the video while the
 * GPU idles. The default renderer adapter is installed from `kiteplayer-mobile`; the widget
 * itself remains backend-agnostic in `kiteplayer-view`.
 *
 * The trade is the classic interop hole: the video is not Compose content, so Compose clip,
 * alpha, rotation and shader effects do not apply to its pixels. When video must behave as a
 * true Compose primitive, use `KiteVideo` from `kiteplayer-compose-video` and read its cost note.
 *
 * The player is never owned here: opening media, playing, seeking and closing stay the
 * caller's. Passing null detaches, and this Composable leaving composition only stops the
 * picture, never the playback.
 */
@Composable
public fun KitePlayerSurface(player: KitePlayer?, modifier: Modifier = Modifier) {
    platformKitePlayerSurface(player, modifier)
}

@Composable
internal expect fun platformKitePlayerSurface(player: KitePlayer?, modifier: Modifier)

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
