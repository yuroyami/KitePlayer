package io.github.yuroyami.kiteplayer.view

import android.app.Activity
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Enters picture in picture with this view's own parameters.
 *
 * The Activity still owns its manifest entry and the viewer still owns the per-app permission, so
 * false means the OS refused rather than that something broke.
 */
public fun KitePlayerView.enterPictureInPicture(activity: Activity): Boolean =
    activity.enterPictureInPictureMode(pictureInPictureParams())

/**
 * Keeps [activity]'s picture-in-picture parameters matching what this view is showing.
 *
 * Parameters are a value the OS reads once, not something it watches, so they go stale as soon as
 * the picture or the play state moves. Auto-enter is where that bites: parameters built once in
 * `onCreate`, while the player is still paused, leave auto-enter off for the whole session and the
 * window never opens by itself.
 *
 * This pushes fresh parameters when the video size, its rotation or the play state changes, and
 * pushes nothing when nothing a window would notice moved. Close it from the Activity's
 * `onDestroy`.
 *
 * @throws IllegalStateException when the view has no player yet.
 */
public fun KitePlayerView.keepPictureInPictureParamsCurrent(
    activity: Activity,
    autoEnterWhilePlaying: Boolean = true,
): AutoCloseable {
    val player = player
        ?: throw IllegalStateException("assign a player to the view before keeping its parameters current")
    // Collected off the main thread and applied on it. Dispatchers.Main would work only for a
    // consumer that also depends on the Android coroutines artifact, which this one does not.
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val main = Handler(Looper.getMainLooper())
    val pump = PipUpdatePump(autoEnterWhilePlaying)
    scope.launch {
        player.state.collect { snapshot ->
            val size = snapshot.videoSize
            val changed = pump.next(
                width = size?.displayWidth ?: 0,
                height = size?.height ?: 0,
                rotationDegrees = videoRotationDegrees,
                status = snapshot.status,
            ) != null
            if (changed) {
                main.post { activity.setPictureInPictureParams(pictureInPictureParams(autoEnterWhilePlaying)) }
            }
        }
    }
    return AutoCloseable { scope.cancel() }
}
