package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import io.github.yuroyami.kiteplayer.KitePlayer

/**
 * One video composable over both rendering products, switchable while media plays.
 *
 * [path] is a request. When it changes, the old presentation leaves composition and detaches
 * identity-checked, then the new one attaches to the same running [player]; the engine keeps
 * playing through the swap and rebuilds a coupled decoder by itself (see
 * KitePlayer.attachRenderer). [onEffectivePath] reports what actually runs, which differs from
 * [path] where a platform cannot honour it (JVM has no native video view). The player is never
 * owned here: opening media, playing, seeking and closing stay the caller's, exactly like
 * [KitePlayerSurface].
 */
@Composable
public fun KitePlayerVideo(
    player: KitePlayer?,
    modifier: Modifier = Modifier,
    path: KiteRenderPath = KiteRenderPath.Auto,
    onEffectivePath: ((KiteRenderPath) -> Unit)? = null,
) {
    val effective = resolveRenderPath(path)
    val currentOnEffectivePath by rememberUpdatedState(onEffectivePath)
    SideEffect { currentOnEffectivePath?.invoke(effective) }
    key(effective) {
        when (effective) {
            KiteRenderPath.NativeView -> KitePlayerSurface(player = player, modifier = modifier)
            KiteRenderPath.ComposeCanvas -> ComposeCanvasVideo(player = player, modifier = modifier)
            KiteRenderPath.Auto -> error("resolveRenderPath must never return Auto")
        }
    }
}

/** Resolves [requested] to the path this platform runs. Never returns [KiteRenderPath.Auto]. */
internal expect fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath

/** The platform's best [KiteVideoState]: window-bound GPU on Android, portable elsewhere. */
@Composable
internal expect fun rememberPlatformKiteVideoState(): KiteVideoState

@Composable
private fun ComposeCanvasVideo(player: KitePlayer?, modifier: Modifier) {
    val videoState = rememberPlatformKiteVideoState()

    KiteVideo(state = videoState, modifier = modifier)

    LaunchedEffect(player, videoState) {
        val currentPlayer = player ?: return@LaunchedEffect
        // One frame so KiteVideo has laid out and, on Android, bound its GPU path to the window.
        withFrameNanos { }
        currentPlayer.attachRenderer(videoState.renderer)
    }
    DisposableEffect(player, videoState) {
        onDispose {
            try {
                player?.detachRenderer(expected = videoState.renderer)
            } catch (_: IllegalStateException) {
                // A closed player refuses every command; closing already detached everything.
            }
        }
    }
}
