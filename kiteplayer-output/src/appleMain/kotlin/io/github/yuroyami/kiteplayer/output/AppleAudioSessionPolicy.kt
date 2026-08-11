package io.github.yuroyami.kiteplayer.output

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/** Who owns the process-wide iOS audio-session category and activation policy. */
public enum class AppleAudioSessionPolicy {
    /** KitePlayer acquires a shared playback lease around every open Apple audio sink. */
    ManagedPlayback,

    /** The embedding application configures and activates its audio session. */
    ApplicationManaged,
}

internal interface AppleAudioSessionController {
    fun setPlaybackCategory()
    fun setActive(active: Boolean, notifyOthers: Boolean)
}

internal fun interface AppleAudioSessionLease {
    fun close()
}

internal expect fun platformAppleAudioSessionController(): AppleAudioSessionController

internal val sharedAppleAudioSessionLeaseManager: AppleAudioSessionLeaseManager by lazy {
    AppleAudioSessionLeaseManager(platformAppleAudioSessionController())
}

/**
 * Process-wide audio-session ownership shared by every Apple sink.
 *
 * Session calls are lifecycle work, never render-callback work. The first managed lease configures and
 * activates the session; the last one deactivates it. Application-managed leases deliberately do
 * neither. Activation is part of the transaction: a refusal leaves the count at zero so the next open
 * retries rather than inheriting a session that was never activated.
 */
internal class AppleAudioSessionLeaseManager(
    private val controller: AppleAudioSessionController,
) {
    private val lock = SynchronizedObject()
    private var leases: Int = 0

    internal val activeLeaseCount: Int get() = synchronized(lock) { leases }

    fun acquire(policy: AppleAudioSessionPolicy): AppleAudioSessionLease {
        if (policy == AppleAudioSessionPolicy.ApplicationManaged) return ApplicationManagedLease

        synchronized(lock) {
            if (leases == 0) {
                controller.setPlaybackCategory()
                controller.setActive(active = true, notifyOthers = false)
            }
            leases++
        }
        return ManagedLease(this)
    }

    private fun release() {
        synchronized(lock) {
            check(leases > 0) { "an Apple audio-session lease was released without being acquired" }
            leases--
            if (leases == 0) {
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
    }

    private object ApplicationManagedLease : AppleAudioSessionLease {
        override fun close() = Unit
    }
}
