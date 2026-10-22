package io.github.yuroyami.kiteplayer.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** The system's previous button starts the song again, or goes back one near its start (#424). */
class PreviousPressTest {

    @Test
    fun tenSecondsIntoTheSecondItemStartsItAgain() {
        assertEquals(PreviousPress.Restart, previousPress(10.seconds, canSeek = true, hasPrevious = true))
    }

    @Test
    fun oneSecondIntoTheSecondItemGoesBackOne() {
        assertEquals(PreviousPress.GoBack, previousPress(1.seconds, canSeek = true, hasPrevious = true))
        assertEquals(PreviousPress.GoBack, previousPress(2999.milliseconds, canSeek = true, hasPrevious = true))
        assertEquals(PreviousPress.Restart, previousPress(3.seconds, canSeek = true, hasPrevious = true))
    }

    @Test
    fun theFirstItemOrASingleFileStartsAgainAtAnyPosition() {
        for (position in listOf(Duration.ZERO, 1.seconds, 10.seconds)) {
            assertEquals(PreviousPress.Restart, previousPress(position, canSeek = true, hasPrevious = false), "at $position")
        }
    }

    @Test
    fun anItemThatCannotSeekCanOnlyGoBack() {
        assertEquals(PreviousPress.GoBack, previousPress(10.seconds, canSeek = false, hasPrevious = true))
        assertEquals(PreviousPress.Nothing, previousPress(10.seconds, canSeek = false, hasPrevious = false))
    }

    @Test
    fun theButtonIsOfferedWheneverAPressDoesSomething() {
        assertTrue(state(canSeek = true, hasPrevious = false).offersPrevious, "a single seekable file can start again")
        assertTrue(state(canSeek = false, hasPrevious = true).offersPrevious)
        assertFalse(state(canSeek = false, hasPrevious = false).offersPrevious, "a lone live stream has nothing to go back to")
    }

    private fun state(canSeek: Boolean, hasPrevious: Boolean) = MediaSessionState(
        phase = MediaSessionPhase.Playing,
        position = 10.seconds,
        duration = 60.seconds,
        speed = 1.0,
        canSeek = canSeek,
        hasVideo = false,
        title = null,
        artist = null,
        album = null,
        hasNext = false,
        hasPrevious = hasPrevious,
    )
}
