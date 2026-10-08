package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioContent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Who owns the process-wide iOS audio-session category and activation policy. */
public enum class AppleAudioSessionPolicy {
    /**
     * KitePlayer acquires a shared playback lease around every open Apple audio sink. The session
     * mixes with other apps' sound from the open until something first plays, so a paused open
     * leaves another app's music playing and the first play stops it. An item with more than two
     * channels tells the system so and asks the route for that many (#502), which is what lets a
     * receiver take surround and lets headphones that place sound in space do it.
     */
    ManagedPlayback,

    /**
     * The embedding application configures and activates its audio session. For surround it calls
     * `setSupportsMultichannelContent` and `setPreferredOutputNumberOfChannels` itself, before the
     * player opens: the sink opens with the channels the route offers at that moment.
     */
    ApplicationManaged,
}

internal interface AppleAudioSessionController {
    /**
     * Sets the playback category with the mode [content] asks for: the default mode for music,
     * `spokenAudio` for speech and `moviePlayback` for a film (#446). [mixesWithOthers] lets other
     * apps' sound go on while this session is active (#436).
     */
    fun setPlaybackCategory(content: AudioContent, mixesWithOthers: Boolean)
    fun setActive(active: Boolean, notifyOthers: Boolean)

    /** Tells the system whether this app's sound has more than two channels (#502). */
    fun setMultichannelContent(offered: Boolean) = Unit

    /**
     * Asks the active route for [channels] output channels, or for as many as it offers when that
     * is fewer (#502). The route decides, and the output unit reports what it gave.
     */
    fun preferOutputChannels(channels: Int) = Unit
}

internal fun interface AppleAudioSessionLease {
    fun close()

    /**
     * Turns the session on for playback, before every start and resume. The first one stops the
     * session mixing with other apps, which is when their sound stops (#436). It also turns the
     * session on again after an interruption such as a phone call, which iOS ends by turning an
     * app's session off and nothing turns it back on by itself.
     */
    fun reactivate() = Unit
}

internal expect fun platformAppleAudioSessionController(): AppleAudioSessionController

internal val sharedAppleAudioSessionLeaseManager: AppleAudioSessionLeaseManager by lazy {
    AppleAudioSessionLeaseManager(platformAppleAudioSessionController())
}

/**
 * Process-wide audio-session ownership shared by every Apple sink.
 *
 * Session calls are lifecycle work, never render-callback work. The first managed lease configures and
 * activates the session; the last one deactivates it. The session is active from the open, because
 * the output unit is made against it, but it mixes with other apps until something first plays, so
 * opening a paused item leaves another app's music playing and the first play stops it (#436). A
 * lease taken for other content while the session is active sets the mode again, so the item that
 * opened last decides it. The same item decides the channels: one with more than two declares
 * multichannel content before the activation and asks the route for its count after it, and a
 * stereo one that follows takes both back (#502). A refusal of either leaves the open alone, because
 * the output unit bounds its channels by what the route really gives. Application-managed leases
 * deliberately do none of this. Activation is part of
 * the transaction: a refusal leaves the count at zero so the next open retries rather than
 * inheriting a session that was never activated.
 */
internal class AppleAudioSessionLeaseManager(
    private val controller: AppleAudioSessionController,
) {
    private val lock = SynchronizedObject()
    private var leases: Int = 0
    private var mode: AudioContent? = null

    /* True once something has played since the session was activated, which is when it stopped mixing. */
    private var claimed = false

    /* True while the system has been told this app plays more than two channels. */
    private var multichannel = false

    /* True while the route has been asked for more than two channels. */
    private var widened = false

    internal val activeLeaseCount: Int get() = synchronized(lock) { leases }

    /** @param channels how many channels the item about to open carries. */
    fun acquire(
        policy: AppleAudioSessionPolicy,
        content: AudioContent = AudioContent.Movie,
        channels: Int = 2,
    ): AppleAudioSessionLease {
        if (policy == AppleAudioSessionPolicy.ApplicationManaged) return ApplicationManagedLease

        synchronized(lock) {
            if (leases == 0) {
                controller.setPlaybackCategory(content, mixesWithOthers = true)
                declareChannels(channels)
                controller.setActive(active = true, notifyOthers = false)
                mode = content
            } else {
                // The session is already active, so a refused mode change leaves the one it had
                // rather than refusing the open: the sound is right, only its processing is not.
                if (mode != content &&
                    runCatching { controller.setPlaybackCategory(content, mixesWithOthers = !claimed) }.isSuccess
                ) {
                    mode = content
                }
                declareChannels(channels)
            }
            askRouteFor(channels)
            leases++
        }
        return ManagedLease(this)
    }

    /* Before the activation, as VLC does it: the route is chosen with the declaration in hand. */
    private fun declareChannels(channels: Int) {
        val surround = channels > 2
        if (surround == multichannel) return
        if (runCatching { controller.setMultichannelContent(surround) }.isSuccess) multichannel = surround
    }

    /* After the activation, because only an active session knows what its route offers. */
    private fun askRouteFor(channels: Int) {
        val surround = channels > 2
        if (!surround && !widened) return
        if (runCatching { controller.preferOutputChannels(if (surround) channels else 2) }.isSuccess) widened = surround
    }

    /**
     * Activates the session again while a managed lease is held. A refusal is not thrown: the
     * device start that follows reports whatever still stops playback.
     */
    fun reactivate() {
        synchronized(lock) {
            if (leases == 0) return
            if (!claimed) {
                // A refusal leaves the session mixing, and the next start tries again.
                claimed = runCatching {
                    controller.setPlaybackCategory(mode ?: AudioContent.Movie, mixesWithOthers = false)
                }.isSuccess
            }
            runCatching { controller.setActive(active = true, notifyOthers = false) }
        }
    }

    private fun release() {
        synchronized(lock) {
            check(leases > 0) { "an Apple audio-session lease was released without being acquired" }
            leases--
            if (leases == 0) {
                mode = null
                claimed = false
                // The next app on this route must not inherit a width or a claim nobody holds.
                if (widened) runCatching { controller.preferOutputChannels(2) }
                if (multichannel) runCatching { controller.setMultichannelContent(false) }
                widened = false
                multichannel = false
                controller.setActive(active = false, notifyOthers = true)
            }
        }
    }

    private class ManagedLease(
        private val manager: AppleAudioSessionLeaseManager,
    ) : SynchronizedObject(), AppleAudioSessionLease {
        private var closed: Boolean = false

        override fun close() {
            val release = synchronized(this) {
                if (closed) false else {
                    closed = true
                    true
                }
            }
            if (release) manager.release()
        }

        override fun reactivate() {
            if (!synchronized(this) { closed }) manager.reactivate()
        }
    }

    private object ApplicationManagedLease : AppleAudioSessionLease {
        override fun close() = Unit
    }
}
