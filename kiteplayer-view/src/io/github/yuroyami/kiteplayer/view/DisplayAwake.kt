package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerSnapshot

/** True while [snapshot] plays video, which is when a view showing it keeps the display awake. */
internal fun playsVideo(snapshot: PlayerSnapshot?): Boolean =
    snapshot != null && snapshot.status == PlaybackStatus.Playing && snapshot.videoSize != null

/**
 * The display hold of one platform view. The view reports whether the setting is on, whether it is
 * on screen and whether its player plays video, and this calls [hold] and [release] once for each
 * change of the three together.
 *
 * Not thread-safe. Every call comes from the platform's main thread, like the view's own callbacks.
 */
internal class DisplayAwakeHolder(
    private val hold: () -> Unit,
    private val release: () -> Unit,
) {
    var enabled: Boolean = true
        set(value) {
            field = value
            update()
        }

    var onScreen: Boolean = false
        set(value) {
            field = value
            update()
        }

    var playing: Boolean = false
        set(value) {
            field = value
            update()
        }

    /** True between a [hold] and the [release] that answers it. */
    var holding: Boolean = false
        private set

    private fun update() {
        val wanted = enabled && onScreen && playing
        if (wanted == holding) return
        holding = wanted
        if (wanted) hold() else release()
    }
}
