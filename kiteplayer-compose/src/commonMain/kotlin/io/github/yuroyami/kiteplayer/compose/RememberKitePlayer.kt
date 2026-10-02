package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.remember
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlayerConfig

/**
 * A player that belongs to this place in the composition: built with `KitePlayer(config)` the
 * first time, and closed when this call leaves the composition.
 *
 * ```kotlin
 * val player = rememberKitePlayer()
 * KitePlayerVideo(player, Modifier.fillMaxSize())
 * LaunchedEffect(url) {
 *     player.open(MediaItem(url))
 *     player.play()
 * }
 * ```
 *
 * [config] is read once. A later, different config does not rebuild the player, because a rebuild
 * would stop what is playing. On Android, a configuration change such as a rotation recreates the
 * activity, so its composition leaves and this player closes. Hold the player in a `ViewModel`
 * when it must outlive that.
 *
 * @throws PlaybackException as `KitePlayer(config)` does, when the platform has no default stack.
 */
@Composable
public fun rememberKitePlayer(config: PlayerConfig = PlayerConfig()): KitePlayer =
    remember { ComposedPlayer(KitePlayer(config)) }.player

/** Closes the player when the composition forgets it, and also when it never kept it. */
private class ComposedPlayer(val player: KitePlayer) : RememberObserver {
    override fun onRemembered() = Unit

    override fun onForgotten() {
        player.close()
    }

    // A composition that was abandoned before it applied never remembered the player either.
    override fun onAbandoned() {
        player.close()
    }
}
