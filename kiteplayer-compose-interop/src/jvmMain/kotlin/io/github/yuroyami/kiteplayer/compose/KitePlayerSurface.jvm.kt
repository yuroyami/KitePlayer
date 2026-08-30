package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.mobile.installDesktopRenderer
import io.github.yuroyami.kiteplayer.view.KitePlayerAwtView

/**
 * Hosts the desktop platform video view, an AWT canvas painted off the Compose frame clock.
 *
 * **Compose content drawn over this cannot receive mouse input.** macOS hands a click to the
 * topmost NATIVE view under the pointer, and this canvas is one, so anything Compose paints above
 * it afterwards is invisible to that decision. It is not a bug here and it cannot be fixed here:
 * controls that must be clickable ON TOP of the video belong in a borderless window owned by this
 * window, and controls that do not overlap the video need nothing special. Measured across seven
 * arrangements on 2026-08-30, in `kiteplayer-sample-desktop/INTEROP-SPIKE.md`.
 *
 * The player is never owned here, exactly as on the other platforms: opening media, playing,
 * seeking and closing stay the caller's, and this composable leaving composition stops the
 * picture rather than the playback.
 */
@Composable
internal actual fun platformKitePlayerSurface(player: KitePlayer?, modifier: Modifier) {
    val view = androidx.compose.runtime.remember {
        KitePlayerAwtView().also { it.installDesktopRenderer() }
    }
    SwingPanel(
        factory = { view },
        modifier = modifier,
        update = { it.player = player },
    )
    DisposableEffect(view) {
        // The view holds the renderer, and the renderer holds a canvas the window is about to
        // take away. Releasing here is what closes that generation on the composable's terms
        // rather than waiting for AWT to tear the peer down underneath it.
        onDispose { view.release() }
    }
}
