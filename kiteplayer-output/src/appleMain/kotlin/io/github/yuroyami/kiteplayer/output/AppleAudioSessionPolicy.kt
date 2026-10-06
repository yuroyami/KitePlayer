package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.AudioContent
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Who owns the process-wide iOS audio-session category and activation policy. */
public enum class AppleAudioSessionPolicy {
    /**
     * KitePlayer acquires a shared playback lease around every open Apple audio sink. The session
     * mixes with other apps' sound from the open until something first plays, so a paused open
     * leaves another app's music playing and the first play stops it.
     */
    ManagedPlayback,

    /** The embedding application configures and activates its audio session. */
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
 * opened last decides it. Application-managed leases deliberately do neither. Activation is part of
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

    internal val activeLeaseCount: Int get() = synchronized(lock) { leases }

    fun acquire(policy: AppleAudioSessionPolicy, content: AudioContent = AudioContent.Movie): AppleAudioSessionLease {
        if (policy == AppleAudioSessionPolicy.ApplicationManaged) return ApplicationManagedLease

        synchronized(lock) {
            if (leases == 0) {
                controller.setPlaybackCategory(content, mixesWithOthers = true)
                controller.setActive(active = true, notifyOthers = false)
                mode = content
            } else if (mode != content) {
                // The session is already active, so a refused mode change leaves the one it had
                // rather than refusing the open: the sound is right, only its processing is not.
                if (runCatching { controller.setPlaybackCategory(content, mixesWithOthers = !claimed) }.isSuccess) {
                    mode = content
                }
            }
            leases++
        }
        return ManagedLease(this)
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
