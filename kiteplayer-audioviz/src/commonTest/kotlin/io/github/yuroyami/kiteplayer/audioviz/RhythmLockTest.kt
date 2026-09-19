package io.github.yuroyami.kiteplayer.audioviz

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RhythmLockTest {
    @Test
    fun entryAndExitRequireSustainedEvidenceWithAHysteresisBand() {
        val lock = RhythmLock()
        repeat(99) { lock.update(0.6f, 0.01f) }
        assertFalse(lock.usable)
        lock.update(0.6f, 0.02f)
        assertTrue(lock.usable)
        repeat(200) { lock.update(0.5f, 0.01f) }
        assertTrue(lock.usable, "the middle band retains a lock")
        repeat(99) { lock.update(0.39f, 0.01f) }
        assertTrue(lock.usable)
        lock.update(0.39f, 0.02f)
        assertFalse(lock.usable)
        repeat(200) { lock.update(0.5f, 0.01f) }
        assertFalse(lock.usable, "the middle band cannot acquire a lock")
    }

    @Test
    fun expiryAndDisagreementDropTheLockWithoutWaitingForTheExitTimer() {
        val lock = RhythmLock()
        repeat(101) { lock.update(0.9f, 0.01f) }
        assertTrue(lock.usable)
        lock.update(0.9f, 0.01f, valid = false)
        assertFalse(lock.usable)
        repeat(101) { lock.update(0.9f, 0.01f) }
        assertTrue(lock.usable)
        lock.reset()
        assertFalse(lock.usable)
        lock.update(0.9f, 0.5f)
        assertFalse(lock.usable, "old entry duration must be cleared")
    }

    @Test
    fun aBriefGoodOrBadReadingDoesNotAccumulateAcrossTheOtherState() {
        val lock = RhythmLock()
        repeat(4) {
            lock.update(0.9f, 0.75f)
            lock.update(0.5f, 0.1f)
        }
        assertFalse(lock.usable)
        repeat(101) { lock.update(0.9f, 0.01f) }
        repeat(4) {
            lock.update(0.2f, 0.75f)
            lock.update(0.5f, 0.1f)
        }
        assertTrue(lock.usable)
    }
}
