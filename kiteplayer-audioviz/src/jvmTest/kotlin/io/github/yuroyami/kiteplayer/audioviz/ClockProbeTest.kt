package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * How far the analysis the picture draws sits from the sound, on a real player.
 *
 * Each analysis carries the time its audio plays at, and the picture asks the player where it is.
 * This plays a clip, reads the picture's clock at twice the display rate, and checks two things: the
 * analysis for that moment is within two frames of it, and the clock advances with elapsed time
 * rather than in the player's own coarse steps. It opens a real device, so it
 * skips itself where there is no mixer or no clip.
 */
class ClockProbeTest {

    private val media: File? = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, MEDIA) },
        File("../testmedia/$MEDIA"),
        File("testmedia/$MEDIA"),
    ).filterNotNull().firstOrNull { it.isFile }

    @Test
    fun `the analysis the picture draws is within two frames of the sound`() = runBlocking {
        val file = media ?: return@runBlocking println("SKIP: no $MEDIA to play")
        if (AudioSystem.getMixerInfo().isEmpty()) return@runBlocking println("SKIP: no audio mixer")

        val player = assertNotNull(KitePlayerPlatform.createOrNull(), "no default desktop player")
        val feed = AudioVizFeed()
        val clock = SmoothClock(
            published = { player.position().inWholeMicroseconds },
            rate = { player.state.value.let { if (it.status == PlaybackStatus.Playing) it.speed else 0.0 } },
            nanos = System::nanoTime,
        )
        try {
            player.attachAudioTap(feed)
            player.open(MediaItem(file.absolutePath))
            player.play()
            val started = withTimeoutOrNull(20.seconds) {
                while (player.position().inWholeMilliseconds < 300) delay(20)
                true
            }
            assertTrue(started == true, "the clip never got past 300 ms")

            val gaps = mutableListOf<Long>()
            val leads = mutableListOf<Long>()
            val playerSteps = mutableListOf<Long>()
            val clockSteps = mutableListOf<Long>()
            val clockErrors = mutableListOf<Long>()
            var lastPlayer = -1L
            var lastClock = -1L
            var lastSampleNanos = -1L
            val until = System.nanoTime() + READ_SECONDS * 1_000_000_000L
            while (System.nanoTime() < until) {
                delay(8)
                val raw = player.position().inWholeMicroseconds
                val now = clock.micros()
                val sampledAt = System.nanoTime()
                if (lastPlayer >= 0 && raw != lastPlayer) playerSteps += raw - lastPlayer
                if (lastClock >= 0) {
                    val step = now - lastClock
                    clockSteps += step
                    // A loaded CI runner may resume delay(8) after 100 ms. Its clock should advance too.
                    val elapsedMicros = (sampledAt - lastSampleNanos) / 1_000
                    clockErrors += abs(step - elapsedMicros)
                }
                lastPlayer = raw
                lastClock = now
                lastSampleNanos = sampledAt
                val aligned = feed.timeline.at(now) ?: continue
                gaps += aligned.ptsMicros - now
                feed.timeline.newest()?.let { leads += it.ptsMicros - now }
            }

            println("analysis the picture draws, against its clock: ${summary(gaps)}")
            println("newest analysis, against the clock (the tap's head start): ${summary(leads)}")
            println("the player's position moves in steps of ${summary(playerSteps)}")
            println("the picture's clock moves in steps of ${summary(clockSteps)}")
            println("clock step error after accounting for elapsed time: ${summary(clockErrors)}")
            assertTrue(gaps.isNotEmpty(), "no analysis ever matched the clock")
            val median = gaps.sorted()[gaps.size / 2]
            assertTrue(abs(median) <= BOUND_MICROS, "the picture's analysis sits ${median / 1000} ms from the sound")
            val coarsest = clockErrors.sorted()[clockErrors.size * 9 / 10]
            assertTrue(coarsest <= BOUND_MICROS, "the picture's clock differs from elapsed time by ${coarsest / 1000} ms")
        } finally {
            player.detachAudioTap(feed)
            player.closeAndAwait()
        }
    }

    private fun summary(values: List<Long>): String {
        if (values.isEmpty()) return "no readings"
        val sorted = values.sorted()
        return "min ${sorted.first() / 1000} ms, median ${sorted[sorted.size / 2] / 1000} ms, " +
            "max ${sorted.last() / 1000} ms (${values.size} readings)"
    }

    private companion object {
        const val MEDIA = "audio-flac.flac"
        const val READ_SECONDS = 3

        /** Two frames at sixty a second. */
        const val BOUND_MICROS = 33_000L
    }
}
