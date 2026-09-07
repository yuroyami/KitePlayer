package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Asking for the sound once, and giving it back once. */
class SessionFocusLifecycleTest {

    @Test
    fun `playing asks once and asks no more`() {
        val lifecycle = SessionFocusLifecycle()
        assertEquals(true, lifecycle.on(PlaybackStatus.Playing))
        assertNull(lifecycle.on(PlaybackStatus.Playing))
        assertNull(lifecycle.on(PlaybackStatus.Buffering))
    }

    @Test
    fun `a pause keeps it so a call can hand it back`() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        assertNull(lifecycle.on(PlaybackStatus.Paused))
        assertNull(lifecycle.on(PlaybackStatus.Playing))
    }

    @Test
    fun `going idle gives it back once`() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        assertEquals(false, lifecycle.on(PlaybackStatus.Idle))
        assertNull(lifecycle.on(PlaybackStatus.Idle))
    }

    @Test
    fun `ending and failing both give it back`() {
        for (status in listOf(PlaybackStatus.Ended, PlaybackStatus.Failed)) {
            val lifecycle = SessionFocusLifecycle()
            lifecycle.on(PlaybackStatus.Playing)
            assertEquals(false, lifecycle.on(status), "$status")
        }
    }

    @Test
    fun `nothing is given back when nothing was asked for`() {
        val lifecycle = SessionFocusLifecycle()
        assertNull(lifecycle.on(PlaybackStatus.Idle))
        assertNull(lifecycle.on(PlaybackStatus.Opening))
        assertFalse(lifecycle.release())
    }

    @Test
    fun `closing while holding gives it back`() {
        val lifecycle = SessionFocusLifecycle()
        lifecycle.on(PlaybackStatus.Playing)
        assertTrue(lifecycle.release())
        assertFalse(lifecycle.release())
    }
}
