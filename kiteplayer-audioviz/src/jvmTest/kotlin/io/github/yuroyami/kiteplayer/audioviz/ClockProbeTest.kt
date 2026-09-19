package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.MediaItem
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
 * How far queued analysis sits from the device-anchored clock, on a real player.
 *
 * This plays a clip and compares feature timestamps with the audible clock's media timestamps.
 * It checks queue alignment, not physical sound/display synchronisation. A loaded runner can miss
 * sampling intervals, and device clock corrections need not produce a constant step between probes.
 * It opens a real device, so it skips itself where there is no mixer or no clip.
 */
class ClockProbeTest {

    private val media: File? = sequenceOf(
        System.getenv("KITEPLAYER_TESTMEDIA")?.let { File(it, MEDIA) },
        File("../testmedia/$MEDIA"),
        File("testmedia/$MEDIA"),
    ).filterNotNull().firstOrNull { it.isFile }

    @Test
    fun `queued analysis follows the device anchored media clock`() = runBlocking {
        val file = media ?: return@runBlocking println("SKIP: no $MEDIA to play")
        if (AudioSystem.getMixerInfo().isEmpty()) return@runBlocking println("SKIP: no audio mixer")

        val player = assertNotNull(KitePlayerPlatform.createOrNull(), "no default desktop player")
        val feed = AudioVizFeed()
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
            var lastPlayer = -1L
            var lastClock = -1L
            val until = System.nanoTime() + READ_SECONDS * 1_000_000_000L
            while (System.nanoTime() < until) {
                delay(8)
                val raw = player.position().inWholeMicroseconds
                val reading = player.audioClock()
                val now = reading.position?.micros ?: continue
                if (feed.timeline.generation != reading.generation) continue
                if (lastPlayer >= 0 && raw != lastPlayer) playerSteps += raw - lastPlayer
                if (lastClock >= 0) clockSteps += now - lastClock
                lastPlayer = raw
                lastClock = now
                val aligned = feed.timeline.at(now) ?: continue
                gaps += aligned.ptsMicros - now
                feed.timeline.newest()?.let { leads += it.ptsMicros - now }
            }

            println("analysis the picture draws, against its clock: ${summary(gaps)}")
            println("newest analysis, against the clock (the tap's head start): ${summary(leads)}")
            println("the player's position moves in steps of ${summary(playerSteps)}")
            println("the picture's clock moves in steps of ${summary(clockSteps)}")
            assertTrue(gaps.isNotEmpty(), "no analysis ever matched the clock")
            val median = gaps.sorted()[gaps.size / 2]
            assertTrue(abs(median) <= BOUND_MICROS, "queued analysis sits ${median / 1000} ms from the audible clock")
        } finally {
            feed.close()
            try {
                player.detachAudioTap(feed)
                player.closeAndAwait()
            } finally {
                feed.awaitClosed()
            }
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
