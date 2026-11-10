package io.github.yuroyami.kiteplayer.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
 * topmost native view; put such controls in an owned overlay window. On the web (wasmJs) there is
 * no native view, so every request resolves to [KiteRenderPath.ComposeCanvas], which draws the
 * picture through `KiteVideo` in Compose's own WebGL context and needs WebGL2. The player is never
 * owned here: opening media, playing, seeking and closing stay the caller's, exactly like
 * [KitePlayerSurface].
 *
 * [onRendererAttached] fires once this composable has handed its video output to [player]. On the
 * Compose canvas path that is one frame after composition, once the attach returned without an
 * exception. On the native view path it means only that the player was handed to the view: the
 * view's own binding then attaches, and a player the view refuses, such as a closed one, or a view
 * with no renderer for this platform, still reports here. Media does not have to wait for it: a
 * player that opened before its output existed moves to the renderer's own decoder when the
 * renderer arrives. It fires again after each path swap, so a one-shot caller must latch it.
 *
 * [keepDisplayAwake] keeps the display from dimming and locking while [player] plays video and
 * this composable is on screen. True by default. A paused or ended player, or one that plays
 * audio only, lets the display sleep again. The desktop accepts it and does nothing, because the
 * desktop JVM has no call for it.
 *
 * [accessibilityVideoLabel] and [accessibilityStateFormat] are what a screen reader says about the
 * video, on both paths: the label, and the state built from the status, the position and the
 * duration. Null keeps the English default, "Video" and `accessibilityStateText`.
 */
@Composable
public fun KitePlayerVideo(
    player: KitePlayer?,
    modifier: Modifier = Modifier,
    path: KiteRenderPath = KiteRenderPath.Auto,
    onEffectivePath: ((KiteRenderPath) -> Unit)? = null,
    onRendererAttached: ((KitePlayer) -> Unit)? = null,
    keepDisplayAwake: Boolean = true,
    accessibilityVideoLabel: String? = null,
    accessibilityStateFormat: ((PlaybackStatus, Duration, Duration?) -> String)? = null,
) {
    val effective = resolveRenderPath(path)
    val currentOnEffectivePath by rememberUpdatedState(onEffectivePath)
    val currentOnRendererAttached by rememberUpdatedState(onRendererAttached)
    // Keyed on the path, so the report runs when the path changes and not after every
    // recomposition, as the attach report below already is (#938).
    LaunchedEffect(effective) { currentOnEffectivePath?.invoke(effective) }
    key(effective) {
        when (effective) {
            KiteRenderPath.NativeView -> NativeViewVideo(
                player,
                modifier,
                keepDisplayAwake = keepDisplayAwake,
                accessibilityVideoLabel = accessibilityVideoLabel,
                accessibilityStateFormat = accessibilityStateFormat,
            ) {
                currentOnRendererAttached?.invoke(it)
            }
            KiteRenderPath.ComposeCanvas -> {
                ComposeCanvasVideo(
                    player = player,
                    modifier = modifier,
                    accessibilityVideoLabel = accessibilityVideoLabel ?: DEFAULT_VIDEO_ACCESSIBILITY_LABEL,
                    accessibilityStateFormat = accessibilityStateFormat ?: ::accessibilityStateText,
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
 * [KitePlayerVideo] with [controls] drawn over the video, for example
 * `KitePlayerVideo(player) { KitePlayerControls(player) }`. Every other parameter is the one of
 * the overload without controls, and [modifier] sizes the video and the controls together.
 *
 * On the desktop a native view takes every click, so controls drawn over
 * [KiteRenderPath.NativeView] receive none there. This overload therefore resolves
 * [KiteRenderPath.Auto] to [KiteRenderPath.ComposeCanvas] on the desktop, so its controls can be
 * clicked (#940), and says once in [io.github.yuroyami.kiteplayer.KiteLog] when it is asked for the
 * native view there. Controls placed beside the video can use the native view, through the
 * overload without controls.
 */
@Composable
public fun KitePlayerVideo(
    player: KitePlayer?,
    modifier: Modifier = Modifier,
    path: KiteRenderPath = KiteRenderPath.Auto,
    onEffectivePath: ((KiteRenderPath) -> Unit)? = null,
    onRendererAttached: ((KitePlayer) -> Unit)? = null,
    keepDisplayAwake: Boolean = true,
    accessibilityVideoLabel: String? = null,
    accessibilityStateFormat: ((PlaybackStatus, Duration, Duration?) -> String)? = null,
    controls: @Composable BoxScope.() -> Unit,
) {
    val overlaid = remember(path) { overlaidControlsPath(path) }
    Box(modifier) {
        KitePlayerVideo(
            player,
            Modifier.matchParentSize(),
            overlaid,
            onEffectivePath,
            onRendererAttached,
            keepDisplayAwake,
            accessibilityVideoLabel,
            accessibilityStateFormat,
        )
        controls()
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
    accessibilityVideoLabel: String? = null,
    accessibilityStateFormat: ((PlaybackStatus, Duration, Duration?) -> String)? = null,
    onRendererAttached: (KitePlayer) -> Unit,
) {
    if (surface != null) {
        surface(player, modifier)
    } else {
        KitePlayerSurface(
            player = player,
            modifier = modifier,
            keepDisplayAwake = keepDisplayAwake,
            accessibilityVideoLabel = accessibilityVideoLabel,
            accessibilityStateFormat = accessibilityStateFormat,
        )
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
    accessibilityVideoLabel: String,
    accessibilityStateFormat: (PlaybackStatus, Duration, Duration?) -> String,
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
    val stateText = remember(player, status, duration, accessibilityStateFormat) {
        accessibilityStateFormat(status, player?.progress?.value?.position ?: Duration.ZERO, duration)
    }
    KiteVideo(
        state = videoState,
        modifier = modifier.semantics {
            contentDescription = accessibilityVideoLabel
            stateDescription = stateText
        },
    )

    LaunchedEffect(player, videoState) {
        val currentPlayer = player ?: return@LaunchedEffect
        // One frame so KiteVideo has laid out and, on Android, bound its GPU path to the window.
        withFrameNanos { }
        try {
            currentPlayer.attachRenderer(videoState.renderer)
        } catch (refused: IllegalStateException) {
            // A closed player refuses every command, and the picture then stays empty, as the
            // detach below already treats a closed player, instead of failing the composition (#603).
            io.github.yuroyami.kiteplayer.KiteLog.log("KitePlayerVideo", "no picture, because the player refused the renderer: ${refused.message}")
            return@LaunchedEffect
        }
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

/**
 * The path for [requested] when controls are drawn over the video: [KiteRenderPath.Auto] becomes
 * the Compose canvas where a native view takes every click, and a native view asked for there is
 * reported once, since its controls will not respond (#940).
 */
internal fun overlaidControlsPath(requested: KiteRenderPath): KiteRenderPath {
    if (!nativeViewTakesOverlayClicks) {
        if (requested == KiteRenderPath.Auto) return KiteRenderPath.ComposeCanvas
        if (requested == KiteRenderPath.NativeView && !nativeViewControlsReported) {
            nativeViewControlsReported = true
            io.github.yuroyami.kiteplayer.KiteLog.log(
                "KitePlayerVideo",
                "controls drawn over the native view receive no clicks on this platform; ask for KiteRenderPath.ComposeCanvas or place them beside the video",
            )
        }
    }
    return requested
}

/** Composition runs on the main thread, so a plain flag; a second report would be harmless. */
private var nativeViewControlsReported = false

/** Whether Compose content drawn over this platform's native video view receives clicks. */
internal expect val nativeViewTakesOverlayClicks: Boolean
