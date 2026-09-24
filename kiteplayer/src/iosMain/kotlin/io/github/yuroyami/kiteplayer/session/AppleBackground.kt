package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.view.KitePlayerPictureInPicture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.darwin.NSObjectProtocol

/**
 * Stops [player] decoding video for a screen nobody is looking at.
 *
 * Watches the two application state notifications. Nothing is registered in the app's own
 * delegate, and nothing is required of it beyond the background audio capability when the policy
 * is [BackgroundPolicy.ContinueAudio].
 *
 * Close the handle with the player.
 */
public fun KitePlayerPlatform.attachBackgroundHandling(
    player: KitePlayer,
    policy: BackgroundPolicy = BackgroundPolicy.ContinueAudio,
): AutoCloseable = AppleBackgroundHandle(player, policy, pictureInPicture = null)

/**
 * Stops [player] decoding video for a screen nobody is looking at, and keeps it on while the small
 * window of [pictureInPicture] shows.
 *
 * The viewer still watches the picture in that window, so leaving the app parks nothing while it
 * shows. When the window closes while the app is away, the policy acts then, and coming back undoes
 * it as usual. A window the system opens after the app has left brings parked video back.
 *
 * Null behaves exactly as the overload without it. Close the handle with the player.
 */
public fun KitePlayerPlatform.attachBackgroundHandling(
    player: KitePlayer,
    policy: BackgroundPolicy = BackgroundPolicy.ContinueAudio,
    pictureInPicture: KitePlayerPictureInPicture?,
): AutoCloseable = AppleBackgroundHandle(player, policy, pictureInPicture)

private class AppleBackgroundHandle(
    player: KitePlayer,
    policy: BackgroundPolicy,
    pictureInPicture: KitePlayerPictureInPicture?,
) : AutoCloseable {

    private val applier = BackgroundApplier(PlayerSessionTarget(player), policy)
    private val center = NSNotificationCenter.defaultCenter
    private val observers = mutableListOf<NSObjectProtocol>()

    /** On the main queue, like the notifications, so the applier is only ever used from one thread. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    init {
        observe(UIApplicationDidEnterBackgroundNotification, foreground = false)
        // Becoming active rather than entering the foreground: the second one also fires on the
        // way through a control centre pull, where the app never actually came back.
        observe(UIApplicationDidBecomeActiveNotification, foreground = true)
        if (pictureInPicture != null) {
            scope.launch { pictureInPicture.active.collect { showing -> applier.handleWindow(showing) } }
        }
    }

    private fun observe(name: String?, foreground: Boolean) {
        observers += center.addObserverForName(
            name = name,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { applier.handle(foreground) },
        )
    }

    override fun close() {
        scope.cancel()
        observers.forEach(center::removeObserver)
        observers.clear()
    }
}
