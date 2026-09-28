package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.view.DEFAULT_VIDEO_ACCESSIBILITY_LABEL
import io.github.yuroyami.kiteplayer.view.accessibilityStateText
import kotlin.time.Duration

/**
 * One video composable over both rendering products, switchable while media plays.
 *
 * [path] is a request. When it changes, the old presentation leaves composition and detaches
 * identity-checked, then the new one attaches to the same running [player]; the engine keeps
 * playing through the swap and rebuilds a coupled decoder by itself (see
 * KitePlayer.attachRenderer). [onEffectivePath] reports what actually runs, which differs from
 * [path] where a platform cannot honour it. On desktop both paths are real, but a native view
 * cannot have clickable Compose content drawn over it, because macOS routes a click to the
 * topmost native view; put such controls in an owned overlay window. The player is never
 * owned here: opening media, playing, seeking and closing stay the caller's, exactly like
 * [KitePlayerSurface].
 *
 * [onRendererAttached] fires once this composable's video output is attached to [player]: the
 * native view attaches as soon as the player is set (its renderer is headless-capable, so
 * decoder selection already sees it), the Compose canvas one frame after composition. A caller
 * that delays media open until output exists releases it here. It fires again after each path
 * swap, so a one-shot caller must latch it.
 *
 * [keepDisplayAwake] keeps the display from dimming and locking while [player] plays video and
 * this composable is on screen. True by default. A paused or ended player, or one that plays
 * audio only, lets the display sleep again. The desktop accepts it and does nothing, because the
 * desktop JVM has no call for it.
 */
@Composable
public fun KitePlayerVideo(
    player: KitePlayer?,
    modifier: Modifier = Modifier,
    path: KiteRenderPath = KiteRenderPath.Auto,
    onEffectivePath: ((KiteRenderPath) -> Unit)? = null,
    onRendererAttached: ((KitePlayer) -> Unit)? = null,
    keepDisplayAwake: Boolean = true,
) {
    val effective = resolveRenderPath(path)
    val currentOnEffectivePath by rememberUpdatedState(onEffectivePath)
    val currentOnRendererAttached by rememberUpdatedState(onRendererAttached)
    SideEffect { currentOnEffectivePath?.invoke(effective) }
    key(effective) {
        when (effective) {
            KiteRenderPath.NativeView -> NativeViewVideo(player, modifier, keepDisplayAwake = keepDisplayAwake) {
                currentOnRendererAttached?.invoke(it)
            }
            KiteRenderPath.ComposeCanvas -> {
                ComposeCanvasVideo(
                    player = player,
                    modifier = modifier,
                    onRendererAttached = { currentOnRendererAttached?.invoke(it) },
                )
                // The native view holds the display itself; the canvas has no view to do it.
                if (keepDisplayAwake) KeepDisplayAwakeWhilePlaying(player)
            }
            KiteRenderPath.Auto -> error("resolveRenderPath must never return Auto")
        }
    }
}

/**
 * The native view path: the platform view, and one report for each attachment. [surface] stands in
 * for the view in tests, because the desktop view is a Swing panel that a headless scene cannot host.
 */
@Composable
internal fun NativeViewVideo(
    player: KitePlayer?,
    modifier: Modifier,
    surface: (@Composable (KitePlayer?, Modifier) -> Unit)? = null,
    keepDisplayAwake: Boolean = true,
    onRendererAttached: (KitePlayer) -> Unit,
) {
    if (surface != null) {
        surface(player, modifier)
    } else {
        KitePlayerSurface(player = player, modifier = modifier, keepDisplayAwake = keepDisplayAwake)
    }
    // Once per player, and again when a path swap composes this afresh, but not on a recomposition:
    // an unkeyed SideEffect here reported an attachment whenever the modifier changed. The view holds
    // the player by now, because the interop view's update block hands it over while the change is
    // applied, before any effect runs.
    val currentOnAttached by rememberUpdatedState(onRendererAttached)
    LaunchedEffect(player) {
        player?.let { currentOnAttached(it) }
    }
}

/** Holds the display awake for as long as [player] plays video and this is composed. */
@Composable
private fun KeepDisplayAwakeWhilePlaying(player: KitePlayer?) {
    val snapshot = player?.state?.collectAsState()?.value ?: return
    if (snapshot.status == PlaybackStatus.Playing && snapshot.videoSize != null) HoldDisplayAwake()
}

/** Keeps the display awake while this is composed: `keepScreenOn` on Android, the idle timer on iOS. */
@Composable
internal expect fun HoldDisplayAwake()

/** Resolves [requested] to the path this platform runs. Never returns [KiteRenderPath.Auto]. */
internal expect fun resolveRenderPath(requested: KiteRenderPath): KiteRenderPath

/** The platform's best [KiteVideoState]: window-bound GPU on Android, portable elsewhere. */
@Composable
internal expect fun rememberPlatformKiteVideoState(): KiteVideoState

@Composable
private fun ComposeCanvasVideo(
    player: KitePlayer?,
    modifier: Modifier,
    onRendererAttached: (KitePlayer) -> Unit,
) {
    val videoState = rememberPlatformKiteVideoState()
    val currentOnRendererAttached by rememberUpdatedState(onRendererAttached)

    // A canvas is an unlabelled area to a screen reader, so it says what the platform views say.
    // The text follows the status and the duration, never the position: a value that changed on
    // every tick would make a screen reader speak continuously.
    val snapshot = player?.state?.collectAsState()?.value
    val status = snapshot?.status ?: PlaybackStatus.Idle
    val duration = snapshot?.duration
    val stateText = remember(player, status, duration) {
        accessibilityStateText(status, player?.progress?.value?.position ?: Duration.ZERO, duration)
    }
    KiteVideo(
        state = videoState,
        modifier = modifier.semantics {
            contentDescription = DEFAULT_VIDEO_ACCESSIBILITY_LABEL
            stateDescription = stateText
        },
    )

    LaunchedEffect(player, videoState) {
        val currentPlayer = player ?: return@LaunchedEffect
        // One frame so KiteVideo has laid out and, on Android, bound its GPU path to the window.
        withFrameNanos { }
        currentPlayer.attachRenderer(videoState.renderer)
        currentOnRendererAttached(currentPlayer)
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
