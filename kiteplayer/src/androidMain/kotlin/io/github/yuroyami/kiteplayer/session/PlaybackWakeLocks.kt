package io.github.yuroyami.kiteplayer.session

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager

/**
 * Holds the locks [WakeLockPolicy] names while the player is active, and nothing otherwise.
 * Main thread only, like the notification handle that drives it.
 */
internal class PlaybackWakeLocks(private val cpu: Lock?, private val wifi: Lock?) {

    /** One Android lock, as the two calls this class makes. */
    internal interface Lock {
        fun acquire()
        fun release()
    }

    private var held = false

    /** Takes the locks when [active] turns true and releases them when it turns false. */
    fun onActive(active: Boolean) {
        if (active == held) return
        held = active
        if (active) {
            cpu?.acquire()
            wifi?.acquire()
        } else {
            wifi?.release()
            cpu?.release()
        }
    }

    /** Releases whatever is held. Called when the notification is closed. */
    fun release() = onActive(false)

    companion object {
        private const val TAG = "KitePlayer:playback"

        /** Which of the two locks [policy] asks for: the processor, then Wi-Fi. */
        fun locksFor(policy: WakeLockPolicy): Pair<Boolean, Boolean> = when (policy) {
            WakeLockPolicy.None -> false to false
            WakeLockPolicy.Local -> true to false
            WakeLockPolicy.Network -> true to true
        }

        fun forPolicy(context: Context, policy: WakeLockPolicy): PlaybackWakeLocks {
            val (wantCpu, wantWifi) = locksFor(policy)
            val cpu = if (!wantCpu) null else context.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
                ?.apply { setReferenceCounted(false) }
                ?.let { lock -> AndroidLock(lock::acquire, lock::release) }
            // A device without Wi-Fi, such as a set-top box on a cable, has no manager to ask.
            @Suppress("DEPRECATION") // media3 uses the same mode; it is the one that keeps throughput up.
            val wifi = if (!wantWifi) null else context.getSystemService(WifiManager::class.java)
                ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG)
                ?.apply { setReferenceCounted(false) }
                ?.let { lock -> AndroidLock(lock::acquire, lock::release) }
            return PlaybackWakeLocks(cpu, wifi)
        }
    }

    private class AndroidLock(private val take: () -> Unit, private val drop: () -> Unit) : Lock {
        override fun acquire() = take()
        override fun release() = drop()
    }
}
