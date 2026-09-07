package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
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
): AutoCloseable = AppleBackgroundHandle(player, policy)

private class AppleBackgroundHandle(
    player: KitePlayer,
    policy: BackgroundPolicy,
) : AutoCloseable {

    private val applier = BackgroundApplier(PlayerSessionTarget(player), policy)
    private val center = NSNotificationCenter.defaultCenter
    private val observers = mutableListOf<NSObjectProtocol>()

    init {
        observe(UIApplicationDidEnterBackgroundNotification, foreground = false)
        // Becoming active rather than entering the foreground: the second one also fires on the
        // way through a control centre pull, where the app never actually came back.
        observe(UIApplicationDidBecomeActiveNotification, foreground = true)
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
        observers.forEach(center::removeObserver)
        observers.clear()
    }
}
