package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.KitePlayer

/**
 * The BASELINE Compose path (D-6): the platform player view, wrapped once.
 *
 * On Android this hosts a [io.github.yuroyami.kiteplayer.phone.KitePlayerView] through
 * `AndroidView`; on iOS a [io.github.yuroyami.kiteplayer.phone.KitePlayerUIView] through
 * `UIKitView`. The picture is presented by the platform's own compositor, which is why this is
 * the path for sustained fullscreen playback: the display controller shows the video while the
 * GPU idles.
 *
 * The trade is the classic interop hole: the video is not Compose content, so Compose clip,
 * alpha, rotation and shader effects do not apply to its pixels. When video must behave as a
 * true Compose primitive, use [KiteVideo], the flagship path, and read its cost note.
 *
 * The player is never owned here: opening media, playing, seeking and closing stay the
 * caller's. Passing null detaches, and this Composable leaving composition only stops the
 * picture, never the playback.
 */
@Composable
public expect fun KitePlayerSurface(player: KitePlayer?, modifier: Modifier = Modifier)
