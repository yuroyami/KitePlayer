package io.github.yuroyami.kiteplayer.session

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The media notification holds the locks while the player plays or buffers, so a stream keeps
 * loading with the screen off, and holds nothing while the player is paused or stopped.
 */
class PlaybackWakeLocksTest {

    private class CountingLock : PlaybackWakeLocks.Lock {
        var held = 0
        var takes = 0
        override fun acquire() {
            held++
            takes++
        }
        override fun release() {
            held--
        }
    }

    @Test
    fun bothLocksAreHeldWhileActiveAndReleasedWhenNot() {
        val cpu = CountingLock()
        val wifi = CountingLock()
        val locks = PlaybackWakeLocks(cpu, wifi)
        locks.onActive(true)
        assertEquals(1, cpu.held)
        assertEquals(1, wifi.held)
        locks.onActive(false)
        assertEquals(0, cpu.held)
        assertEquals(0, wifi.held)
    }

    @Test
    fun theSameStateTwiceTakesNothingTwice() {
        val cpu = CountingLock()
        val locks = PlaybackWakeLocks(cpu, null)
        locks.onActive(true)
        locks.onActive(true)
        assertEquals(1, cpu.takes)
        locks.onActive(false)
        locks.onActive(false)
        assertEquals(0, cpu.held)
    }

    @Test
    fun closingReleasesWhatIsHeld() {
        val cpu = CountingLock()
        val wifi = CountingLock()
        val locks = PlaybackWakeLocks(cpu, wifi)
        locks.onActive(true)
        locks.release()
        assertEquals(0, cpu.held)
        assertEquals(0, wifi.held)
        locks.release()
        assertEquals(0, cpu.held)
    }

    @Test
    fun thePolicyPicksTheLocks() {
        assertEquals(false to false, PlaybackWakeLocks.locksFor(WakeLockPolicy.None))
        assertEquals(true to false, PlaybackWakeLocks.locksFor(WakeLockPolicy.Local))
        assertEquals(true to true, PlaybackWakeLocks.locksFor(WakeLockPolicy.Network))
    }
}
