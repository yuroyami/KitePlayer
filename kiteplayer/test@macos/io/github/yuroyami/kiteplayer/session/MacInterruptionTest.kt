package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.PlayerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Headphones giving way to speakers on a Mac (#503). The audio output reports it and the session
 * pauses, which is the policy every other platform follows.
 *
 * A test process runs no main loop, so the handle's collector never starts here. Each case hands
 * the handle its events directly, which is the call that collector makes.
 */
class MacInterruptionTest {

    @Test
    fun aPlayingPlayerPausesWhenItsSoundBecomesLoud() {
        val target = FakeTarget(playing = true)
        val handle = MacInterruptionHandle(target, InterruptionPolicy())
        handle.on(PlayerEvent.AudioOutputBecameNoisy(target.transportMark))
        assertEquals(listOf("pause"), target.calls)
        handle.close()
        assertEquals(listOf("pause"), target.calls, "nothing brings the sound back by itself")
    }

    /** The event waited in a queue. A press made since it was sent wins over it. */
    @Test
    fun anEventFromBeforeTheListenersLastPressDoesNothing() {
        val target = FakeTarget(playing = true)
        val handle = MacInterruptionHandle(target, InterruptionPolicy())
        val sent = PlayerEvent.AudioOutputBecameNoisy(target.transportMark)
        target.userPauses()
        target.userPlays()
        handle.on(sent)
        assertTrue(target.calls.isEmpty(), "the listener pressed play after the headphones left")
    }

    @Test
    fun aPolicyThatKeepsPlayingAndAPausedPlayerAreLeftAlone() {
        val kept = FakeTarget(playing = true)
        MacInterruptionHandle(kept, InterruptionPolicy(pauseWhenBecomingNoisy = false))
            .on(PlayerEvent.AudioOutputBecameNoisy(kept.transportMark))
        assertTrue(kept.calls.isEmpty(), "the application asked to keep playing")

        val paused = FakeTarget(playing = false)
        MacInterruptionHandle(paused, InterruptionPolicy()).on(PlayerEvent.AudioOutputBecameNoisy(paused.transportMark))
        assertTrue(paused.calls.isEmpty(), "a paused player has nothing to pause")
    }

    @Test
    fun otherEventsDoNothingAndTheSessionOwnsTheHandle() {
        val target = FakeTarget(playing = true)
        MacInterruptionHandle(target, InterruptionPolicy()).on(PlayerEvent.AudioFormatChanged(48_000, 2))
        assertTrue(target.calls.isEmpty())

        val player = KitePlayer()
        player.attachMediaSession().close()
        player.attachMediaSession(interruptions = null).close()
        player.close()
    }
}
