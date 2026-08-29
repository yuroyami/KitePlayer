@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.PlayerEvent
import io.github.yuroyami.kiteplayer.SeekMode
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.AtomicLong
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Seeking real media through the whole player, on real threads, at real speed.
 *
 * The seek machine has its own tests in `kiteplayer-core`, and they run in virtual time against a
 * scripted container: they prove the ordering, the coalescing and the invariants, and they cannot prove
 * that a seek lands where it was asked to in a real file. That needs a real demuxer with a real index, a
 * real decoder holding real frames from before the seek, and a real device that has to be stopped before
 * the ring it reads from is cleared. All of that only exists here.
 *
 * What is asserted is the promise a viewer notices: a precise seek arrives at the frame that was asked
 * for and not at the keyframe before it, a seek past the end finishes the file instead of hanging, and a
 * seek back to the start during playback carries on playing from the start.
 */
class RealMediaSeekTest {

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
    fun `twenty precise seeks in real media each land within one frame of their target`() = runBlocking {
        val player = player()
        // Counted rather than collected into a list, and counted atomically, because the watcher below
        // runs on another thread. A plain `MutableList` appended from `Dispatchers.Default` and read from
        // this thread is an unsynchronised cross-thread access, and its `size` is exactly the value this
        // test asserts on, so the assertion was reading a field nothing published to it. Nothing is lost
        // by counting: the assertion only ever used the number.
        val completions = AtomicLong(0)
        val watcher = CoroutineScope(Dispatchers.Default + SupervisorJob())
        watcher.launch(start = CoroutineStart.UNDISPATCHED) {
            player.events.collect { if (it is PlayerEvent.SeekCompleted) completions.incrementAndGet() }
        }
        try {
            player.open(MediaItem("$mediaDir/sync1080p30.mp4"))
            val snapshot = player.state.value
            val duration = assertNotNull(snapshot.duration, "the fixture declares a duration")
            assertTrue(snapshot.seekable, "and it is seekable, which is what makes this test possible")
            val frameRate = assertNotNull(
                snapshot.tracks.selectedVideo?.let { snapshot.tracks.find(it) }?.frameRate,
                "the fixture declares a frame rate",
            )
            val oneFrame = (1_000_000.0 / frameRate).toLong().microseconds

            // Seeks are made while paused, deliberately: the position then stays where the seek put it, so
            // what is measured is the landing and not the landing plus however long the assertion took.
            val random = Random(23)
            repeat(SEEKS) { attempt ->
                val target = random.nextLong(0, duration.inWholeMilliseconds - 500).milliseconds
                player.seek(target, SeekMode.Precise)
                val landed = player.position()
                val error = landed - target
                assertTrue(
                    abs(error.inWholeMicroseconds) <= oneFrame.inWholeMicroseconds,
                    "seek $attempt asked for $target and landed at $landed, which is $error out: a precise " +
                        "seek must arrive at the frame that was asked for and not at the keyframe before it",
                )
                assertEquals(
                    PlaybackStatus.Paused,
                    player.state.value.status,
                    "seek $attempt left the player somewhere other than paused on its landing frame",
                )
            }
            // Waited for with a bound, and this is the whole correction. `seek` returning does not mean
            // the event announcing it has reached a collector on another dispatcher, so the twentieth
            // completion can still be in flight when the last `seek` returns. Measured during the B1
            // closing gate: this assertion failed once with "19 of 20" in a full-suite run under load,
            // and passed eight out of eight times when the test ran alone, which is the signature of a
            // delivery race and not of a lost event. The wait does not weaken the assertion by one bit:
            // it still requires exactly SEEKS completions, and a player that really emitted nineteen
            // fails the same way five seconds later. The file itself is untouched by B1; the race dates
            // from A5, and a faster seek return only makes it easier to see.
            withTimeoutOrNull(5.seconds) {
                while (completions.value < SEEKS) delay(5.milliseconds)
            }
            assertEquals(
                SEEKS.toLong(),
                completions.value,
                "every seek completed exactly once and said where it landed: ${completions.value} of $SEEKS",
            )
            assertNull(player.state.value.error, "and none of them failed")
        } finally {
            watcher.cancel()
            closeAndAwait(player)
        }
    }

    @Test
    fun `a seek past the end finishes the file rather than hanging`() = runBlocking {
        val player = player()
        try {
            player.open(MediaItem("$mediaDir/sync1080p30.mp4"))
            val duration = assertNotNull(player.state.value.duration)
            player.play()
            delay(300.milliseconds)

            // Past the end is clamped to the end, and the end of the media is then reached the ordinary
            // way: the queues run dry, both decoders drain, the device plays out what it holds, and the
            // status becomes Ended. A seek that answered before that would leave a player that never
            // finishes, which is the failure this bounds.
            player.seek(duration + 2.seconds, SeekMode.Precise)
            val ended = withTimeoutOrNull(20.seconds) {
                player.state.first { it.status == PlaybackStatus.Ended || it.status == PlaybackStatus.Failed }
            }
            assertNotNull(ended, "the player never reached a terminal state after seeking past the end")
            assertEquals(PlaybackStatus.Ended, ended.status, "and it ended rather than failed: ${ended.error}")
            assertNull(ended.error, "cleanly, with nothing retained")
        } finally {
            closeAndAwait(player)
        }
    }

    @Test
    fun `a seek to zero during playback carries on from the start`() = runBlocking {
        val player = player()
        try {
            player.open(MediaItem("$mediaDir/sync1080p30.mp4"))
            player.play()
            val advanced = withTimeoutOrNull(10.seconds) {
                player.progress.first { it.position > 1.seconds }
            }
            assertNotNull(advanced, "playback did not reach one second")

            player.seek(Duration.ZERO, SeekMode.Precise)
            val landed = player.position()
            assertTrue(
                landed < 100.milliseconds,
                "a seek to zero mid play landed at $landed rather than at the start",
            )

            // And it is playback that resumes, not a frozen first frame: the position moves on from the
            // landing under its own steam.
            val resumed = withTimeoutOrNull(10.seconds) {
                player.progress.first { it.position > landed + 300.milliseconds }
            }
            assertNotNull(resumed, "the player stopped advancing after seeking to zero")
            assertEquals(PlaybackStatus.Playing, player.state.value.status)
            assertNull(player.state.value.error)
        } finally {
            closeAndAwait(player)
        }
    }

    /**
     * Closes and waits for the player to say it is idle.
     *
     * Close returns at once and the teardown is bounded and on the player's own threads. A test that
     * returned without waiting would leave one session's device and six threads alive while the next test
     * opened another.
     */
    private suspend fun closeAndAwait(player: KitePlayer) {
        player.close()
        val idle = withTimeoutOrNull(10.seconds) {
            player.state.first { it.status == PlaybackStatus.Idle }
        }
        assertNotNull(idle, "the player did not finish closing: ${player.state.value.error?.message}")
    }

    private companion object {
        /** Enough seeks that a systematic landing error shows up, few enough to stay a fast test. */
        const val SEEKS = 20
    }
}
