package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeBegan
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFAudio.AVAudioSessionRouteChangeReasonKey
import platform.AVFAudio.AVAudioSessionRouteChangeReasonOldDeviceUnavailable
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.darwin.NSObjectProtocol

/**
 * Makes [player] behave when something takes the sound away: a call, another app, or the
 * headphones coming out.
 *
 * iOS reports these as audio session notifications rather than as focus. A call arriving is an
 * interruption that began; the system says on the way out whether resuming is allowed, and this
 * only resumes when it says so. Headphones coming out arrive as a route change whose old device
 * has gone.
 *
 * Close the handle with the player.
 */
public fun KitePlayerPlatform.attachInterruptionHandling(
    player: KitePlayer,
    policy: InterruptionPolicy = InterruptionPolicy(),
): AutoCloseable = AppleInterruptionHandle(player, policy)

private class AppleInterruptionHandle(
    player: KitePlayer,
    policy: InterruptionPolicy,
) : AutoCloseable {

    private val applier = InterruptionApplier(PlayerSessionTarget(player), policy)
    private val center = NSNotificationCenter.defaultCenter
    private val observers = mutableListOf<NSObjectProtocol>()

    init {
        observe(AVAudioSessionInterruptionNotification) { note ->
            interruptionEventFor(
                type = note.number(AVAudioSessionInterruptionTypeKey),
                options = note.number(AVAudioSessionInterruptionOptionKey),
            )?.let(applier::handle)
        }
        observe(AVAudioSessionRouteChangeNotification) { note ->
            if (note.number(AVAudioSessionRouteChangeReasonKey) ==
                AVAudioSessionRouteChangeReasonOldDeviceUnavailable
            ) {
                applier.handle(InterruptionEvent.BecameNoisy)
            }
        }
    }

    private fun observe(name: String?, block: (NSNotification) -> Unit) {
        observers += center.addObserverForName(
            name = name,
            `object` = null,
            queue = NSOperationQueue.mainQueue,
            usingBlock = { note -> note?.let(block) },
        )
    }

    private fun NSNotification.number(key: String?): ULong? =
        (userInfo?.get(key) as? NSNumber)?.unsignedLongValue

    override fun close() {
        observers.forEach(center::removeObserver)
        observers.clear()
        applier.release()
    }
}

/**
 * An audio session interruption, as this player's events.
 *
 * An interruption that began is always treated as a short one: iOS has no permanent form, and the
 * flag that arrives at the end is what decides whether anything plays again. Without the resume
 * flag the end is not reported at all, so nothing starts by itself.
 */
internal fun interruptionEventFor(type: ULong?, options: ULong?): InterruptionEvent? = when (type) {
    AVAudioSessionInterruptionTypeBegan -> InterruptionEvent.LostTransient
    null -> null
    else -> {
        val shouldResume = ((options ?: 0uL) and AVAudioSessionInterruptionOptionShouldResume) != 0uL
        if (shouldResume) InterruptionEvent.Gained else null
    }
}
