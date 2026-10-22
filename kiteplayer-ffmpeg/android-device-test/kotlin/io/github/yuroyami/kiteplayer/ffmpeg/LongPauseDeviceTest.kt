package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.NeedsPushedMedia
import io.github.yuroyami.kiteplayer.PlaybackStatus
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.output.AndroidOutputBackend
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A pause of more than half a minute on Android's own audio output, then play (#13). The position
 * must not jump at the resume, it must advance at the speed of the wall clock afterwards, and the
 * picture must stay with the sound. The harness proves the mechanism on a virtual clock; only a
 * real device has the late callbacks.
 */
@NeedsPushedMedia
internal class LongPauseDeviceTest {

    @Test
    fun aLongPauseResumesWhereItStopped() = runBlocking {
        val mediaDir = formatMatrixMediaDir() ?: error("no media dir on this device")
        val player = KitePlayer.create(
            PlayerConfig(
                backends = Backends(backend = KiteFFmpegMediaBackend(), output = AndroidOutputBackend),
                progressInterval = 50.milliseconds,
                statsInterval = 100.milliseconds,
            ),
        )
        try {
            withTimeout(30_000) { player.open(MediaItem("$mediaDir/sync1080p30.mp4")) }
            player.play()
            waitFor(20.seconds) { player.position() >= 2.seconds }
            player.pause()
            waitFor(5.seconds) { player.state.value.status == PlaybackStatus.Paused }
            delay(300)
            val atPause = player.position()
            delay(35_000)
            val afterPause = player.position()
            assertEquals(atPause, afterPause, "the position moved during the pause")

            val resumed = TimeSource.Monotonic.markNow()
            player.play()
            // Sampled every 20 ms for the first second: the largest step shows a jump at once.
            var last = player.position()
            var largestStep = Duration.ZERO
            while (resumed.elapsedNow() < 1.seconds) {
                delay(20)
                val now = player.position()
                largestStep = maxOf(largestStep, now - last)
                last = now
            }
            delay(2_000)
            val advanced = player.position() - atPause
            val wall = resumed.elapsedNow()
            val drift = player.stats.value.avDrift
            println(
                "LONG PAUSE DEVICE atPause=${atPause.inWholeMilliseconds} largestStepMs=${largestStep.inWholeMilliseconds} " +
                    "advancedMs=${advanced.inWholeMilliseconds} wallMs=${wall.inWholeMilliseconds} avDriftMs=${drift.inWholeMilliseconds}",
            )
            assertTrue(largestStep < 300.milliseconds, "the position jumped ${largestStep.inWholeMilliseconds} ms at the resume")
            // The device's own start-up and buffer delay is allowed, a pause-sized jump is not.
            assertTrue(
                abs((advanced - wall).inWholeMilliseconds) < 500,
                "after the resume the position advanced ${advanced.inWholeMilliseconds} ms in ${wall.inWholeMilliseconds} ms",
            )
            assertTrue(abs(drift.inWholeMilliseconds) <= 45, "the picture is ${drift.inWholeMilliseconds} ms off the sound")
        } finally {
            withTimeout(15_000) { player.closeAndAwait() }
        }
    }

    private suspend fun waitFor(limit: Duration, condition: () -> Boolean) {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            check(started.elapsedNow() < limit) { "timed out after $limit" }
            delay(20)
        }
    }
}
