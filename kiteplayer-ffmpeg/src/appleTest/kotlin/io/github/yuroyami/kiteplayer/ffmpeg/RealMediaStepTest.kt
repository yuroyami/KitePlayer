@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.StepDirection
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Stepping real media one frame at a time, in both directions.
 *
 * The fixture has variable frame rate and one keyframe, at the start. So no step can be computed
 * from a frame period, and every backward step decodes forward from zero and has to stop exactly
 * one frame short of the picture it left.
 */
class RealMediaStepTest {

    /** Set by the Gradle test task. Falls back to a relative path for a hand-run binary. */
    private val mediaDir: String = platform.posix.getenv("KITEPLAYER_TESTMEDIA")
        ?.toKString()
        ?: "testmedia"

    private fun player(): KitePlayer = KitePlayer.create(
        PlayerConfig(
            backends = Backends(
                backend = KiteFFmpegMediaBackend(),
                output = AppleOutputBackend,
            ),
            progressInterval = 50.milliseconds,
            statsInterval = 100.milliseconds,
        ),
    )

    @Test
    fun `ten steps back retrace ten steps forward frame for frame`() = runBlocking {
        val player = player()
        try {
            player.open(MediaItem("$mediaDir/truevfr720.mp4"))
            player.seek(2.seconds, SeekMode.Precise)
            // The walk starts from a step, so every position in it is one a step reported.
            player.stepFrame(StepDirection.Forward)
            val start = player.position()

            val forward = List(STEPS) {
                player.stepFrame(StepDirection.Forward)
                player.position()
            }
            val backward = List(STEPS) {
                player.stepFrame(StepDirection.Backward)
                player.position()
            }

            val walked = listOf(start) + forward
            assertTrue(
                walked.zipWithNext().all { (earlier, later) -> later > earlier },
                "each forward step must show a later frame: $walked",
            )
            assertTrue(
                walked.zipWithNext { earlier, later -> later - earlier }.toSet().size > 1,
                "the gaps between frames must differ, or this walk proves nothing about variable frame rate: $walked",
            )
            assertEquals(
                walked.reversed().drop(1),
                backward,
                "each backward step must show the frame the forward walk showed before it",
            )
            assertEquals(PlaybackStatus.Paused, player.state.value.status, "stepping stays paused")

            // The engine keeps publishing the position while paused, so a step's position must
            // survive the passes after the step returns, not only the first read.
            delay(300.milliseconds)
            assertEquals(start, player.position(), "the position stays on the frame the last step showed")
        } finally {
            closeAndAwait(player)
        }
    }

    @Test
    fun `a backward step from the first frame refuses and moves nothing`() = runBlocking {
        val player = player()
        try {
            player.open(MediaItem("$mediaDir/truevfr720.mp4"))

            assertFailsWith<IllegalStateException> { player.stepFrame(StepDirection.Backward) }

            assertEquals(Duration.ZERO, player.position(), "a refused step leaves the first frame on screen")
            player.stepFrame(StepDirection.Forward)
            assertTrue(player.position() > Duration.ZERO, "and a forward step still works after it")
        } finally {
            closeAndAwait(player)
        }
    }

    /** Close returns at once, so the next test waits for this player's threads to finish first. */
    private suspend fun closeAndAwait(player: KitePlayer) {
        player.close()
        val idle = withTimeoutOrNull(10.seconds) {
            player.state.first { it.status == PlaybackStatus.Idle }
        }
        assertNotNull(idle, "the player did not finish closing: ${player.state.value.error?.message}")
    }

    private companion object {
        const val STEPS = 10
    }
}
