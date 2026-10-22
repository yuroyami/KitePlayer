package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A member that posts to the engine and returns cannot throw what the engine decides later. It
 * checks its arguments before it posts, and the engine publishes a refusal as a warning.
 */
class FireAndForgetRefusalTest {

    private fun refusals(harness: CoreHarness, member: String): List<PlaybackWarning.CommandRefused> =
        harness.core.warningHistory().map { it.warning }
            .filterIsInstance<PlaybackWarning.CommandRefused>().filter { it.member == member }

    @Test
    fun `setSleepTimer refuses a timer that is not in the future and keeps the armed one`() = runTest {
        val harness = CoreHarness(this)
        val player = KitePlayer(harness.core)
        player.open(MediaItem("scripted://sleep"))
        player.setSleepTimer(SleepTimer.After(30.seconds))
        harness.run(100.milliseconds)

        assertFailsWith<IllegalArgumentException> { player.setSleepTimer(SleepTimer.After(Duration.ZERO)) }
        assertFailsWith<IllegalArgumentException> { player.setSleepTimer(SleepTimer.After((-1).seconds)) }
        assertFailsWith<IllegalArgumentException> { player.setSleepTimer(SleepTimer.EndOfItem, fade = (-1).seconds) }
        harness.run(100.milliseconds)
        assertEquals(SleepTimer.After(30.seconds), player.state.value.sleepTimer, "a refused call replaced the armed timer")
        harness.close()
    }

    @Test
    fun `setAbLoop on an unseekable source publishes its refusal`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(seekable = false))
        val player = KitePlayer(harness.core)
        player.open(MediaItem("scripted://live"))
        player.setAbLoop(1.seconds, 2.seconds)
        harness.run(100.milliseconds)

        assertEquals(1, refusals(harness, "setAbLoop").size, "the refusal was not published")
        assertNull(player.state.value.abLoopA, "a refused loop was armed")
        harness.close()
    }

    @Test
    fun `a loop armed before an unseekable open is reported at the open`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(seekable = false))
        val player = KitePlayer(harness.core)
        player.setAbLoop(1.seconds, 2.seconds)
        harness.run(100.milliseconds)
        assertEquals(0, refusals(harness, "setAbLoop").size, "arming with no media open is legal")

        player.open(MediaItem("scripted://live"))
        assertEquals(1, refusals(harness, "setAbLoop").size, "the open did not say the loop cannot run")
        assertEquals(1.seconds, player.state.value.abLoopA, "the loop belongs to the player and stays armed")
        harness.close()
    }
}
