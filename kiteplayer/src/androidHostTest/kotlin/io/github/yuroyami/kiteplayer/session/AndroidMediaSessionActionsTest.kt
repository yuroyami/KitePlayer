package io.github.yuroyami.kiteplayer.session

import android.media.session.PlaybackState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * The buttons the session offers, worked out from what the player can do right now.
 *
 * A next button that is offered when there is nothing next is worse than no button: it looks
 * usable, does nothing, and gives nobody a reason.
 */
class AndroidMediaSessionActionsTest {

    private fun state(
        canSeek: Boolean = true,
        hasNext: Boolean = false,
        hasPrevious: Boolean = false,
    ) = MediaSessionState(
        playing = true,
        position = 5.seconds,
        duration = 60.seconds,
        speed = 1.0,
        canSeek = canSeek,
        title = "A Holiday",
        artist = null,
        album = null,
        hasNext = hasNext,
        hasPrevious = hasPrevious,
    )

    private fun Long.has(action: Long) = (this and action) != 0L

    @Test
    fun `play and pause are always offered`() {
        val actions = actionsFor(state(canSeek = false))
        assertEquals(true, actions.has(PlaybackState.ACTION_PLAY))
        assertEquals(true, actions.has(PlaybackState.ACTION_PAUSE))
        assertEquals(true, actions.has(PlaybackState.ACTION_PLAY_PAUSE))
        assertEquals(true, actions.has(PlaybackState.ACTION_STOP))
    }

    @Test
    fun `a live stream offers no scrubbing and no skipping within it`() {
        val actions = actionsFor(state(canSeek = false))
        assertEquals(false, actions.has(PlaybackState.ACTION_SEEK_TO))
        assertEquals(false, actions.has(PlaybackState.ACTION_FAST_FORWARD))
        assertEquals(false, actions.has(PlaybackState.ACTION_REWIND))
    }

    @Test
    fun `a seekable file offers scrubbing and both skips`() {
        val actions = actionsFor(state(canSeek = true))
        assertEquals(true, actions.has(PlaybackState.ACTION_SEEK_TO))
        assertEquals(true, actions.has(PlaybackState.ACTION_FAST_FORWARD))
        assertEquals(true, actions.has(PlaybackState.ACTION_REWIND))
    }

    @Test
    fun `next and previous follow the queue and not the file`() {
        assertEquals(false, actionsFor(state()).has(PlaybackState.ACTION_SKIP_TO_NEXT))
        assertEquals(true, actionsFor(state(hasNext = true)).has(PlaybackState.ACTION_SKIP_TO_NEXT))
        assertEquals(false, actionsFor(state()).has(PlaybackState.ACTION_SKIP_TO_PREVIOUS))
        assertEquals(
            true,
            actionsFor(state(hasPrevious = true)).has(PlaybackState.ACTION_SKIP_TO_PREVIOUS),
        )
    }
}
