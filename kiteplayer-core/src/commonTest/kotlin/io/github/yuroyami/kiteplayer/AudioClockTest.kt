package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

class AudioClockTest {
    @Test
    fun `audible reading includes a matching host timestamp and rate`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        player.play()
        harness.run(300.milliseconds)

        val reading = player.audioClock()
        val position = assertNotNull(reading.position, "playing audio needs an audible anchor")
        assertEquals(harness.clock.nanos(), reading.hostTimeNanos)
        assertEquals(1.0, reading.rate)
        assertTrue(position.micros in 100_000L..500_000L, "audible time was $position")
        assertEquals(harness.sink.latencyQuality, reading.quality)
        harness.run(20.milliseconds)
        val later = player.audioClock()
        val elapsed = (later.hostTimeNanos - reading.hostTimeNanos) / 1_000L
        val advanced = assertNotNull(later.position).micros - position.micros
        assertTrue(kotlin.math.abs(advanced - elapsed) < 2_000L, "advanced $advanced us in $elapsed us")
        harness.close()
    }

    @Test
    fun `a requested seek target never becomes an audible reading before landing`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        player.play()
        harness.run(300.milliseconds)
        val before = player.audioClock()
        assertNotNull(before.position)

        player.seekLater(5.seconds)
        assertEquals(5.seconds, player.position(), "the seek bar should reflect the request immediately")
        val requested = player.audioClock()
        assertTrue(requested.position == null || requested.position.micros < 1_000_000L)
        harness.run(300.milliseconds)
        val landed = player.audioClock()
        assertTrue(landed.generation > before.generation, "seek must retire the old audio timeline")
        assertTrue(assertNotNull(landed.position).micros in 5_000_000L..5_500_000L)
        harness.close()
    }

    @Test
    fun `pause freezes the audible mapping and a rate change replaces it`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        player.play()
        harness.run(300.milliseconds)
        player.pause()
        harness.run(30.milliseconds)
        val paused = player.audioClock()
        assertNotNull(paused.position)
        assertEquals(0.0, paused.rate)
        harness.run(500.milliseconds)
        assertEquals(paused.position, player.audioClock().position)

        player.setSpeed(2.0)
        player.play()
        harness.run(300.milliseconds)
        val fast = player.audioClock()
        assertNotNull(fast.position)
        assertEquals(2.0, fast.rate)
        assertTrue(fast.generation > paused.generation)
        harness.close()
    }

    @Test
    fun `after resume the reading never runs ahead of the paused position plus the time since play`() = runTest {
        // Until the device reports again, the ring still holds the anchor from before the pause.
        // Applied after play, that anchor counted the whole pause as played time.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 8_000_000))
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        player.play()
        harness.run(1.seconds)
        player.pause()
        harness.run(350.milliseconds)
        val paused = player.position()

        player.play()
        val playedAtNanos = harness.clock.nanos()
        repeat(10) {
            harness.run(10.milliseconds)
            val limit = paused + (harness.clock.nanos() - playedAtNanos).nanoseconds + 5.milliseconds
            val position = player.position()
            val audible = assertNotNull(player.audioClock().position).asDuration
            assertTrue(position <= limit, "the position read $position, above $limit, after a pause at $paused")
            assertTrue(audible <= limit, "the audible clock read $audible, above $limit, after a pause at $paused")
        }
        harness.close()
    }

    @Test
    fun `idle video only and closed players have no audible anchor`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(hasAudio = false))
        val player = KitePlayer(harness.core)
        assertFalse(player.audioClock().isValid)
        harness.openWithRenderer()
        player.play()
        harness.run(300.milliseconds)
        assertNull(player.audioClock().position, "video position is not an audio clock")
        harness.close()
        assertNull(player.audioClock().position)
    }

    @Test
    fun `zero and negative timestamps are valid and scale display delay by rate`() {
        for (micros in listOf(-25_000L, 0L, 25_000L)) {
            for (rate in listOf(0.0, 0.5, 1.0, 2.0)) {
                val reading = AudioClockSnapshot(Pts(micros), 1_000_000_000L, rate, Generation(7), LatencyQuality.Exact)
                assertTrue(reading.isValid)
                val projected = reading.at(1_020_000_000L)
                assertEquals(Pts(micros + (rate * 20_000L).toLong()), projected.position)
                assertEquals(Generation(7), projected.generation)
                assertEquals(LatencyQuality.Exact, projected.quality)
            }
        }
    }

    @Test
    fun `tap identities match the clock across seeks track switches and reopen`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(
            durationUs = 8_000_000,
            additionalAudioTracks = listOf(ScriptedAudioTrack(3, marker = 0.5f)),
        ))
        val player = KitePlayer(harness.core)
        val seen = mutableListOf<Generation>()
        val cuts = mutableListOf<Generation>()
        player.attachAudioTap(object : AudioTap {
            override fun onAudio(pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
                error("the engine must call the callback carrying a generation")
            }

            override fun onAudio(generation: Generation, pts: Pts, interleaved: FloatArray, frames: Int, format: AudioFormat) {
                seen += generation
                assertEquals(cuts.last(), generation, "a block belongs to the announced timeline")
            }

            override fun onDiscontinuity(generation: Generation) {
                cuts += generation
            }
        })
        harness.openWithRenderer()
        player.play()
        harness.run(300.milliseconds)
        val initial = player.audioClock().generation
        assertEquals(initial, seen.last())

        player.seek(2.seconds)
        harness.run(300.milliseconds)
        val sought = player.audioClock().generation
        assertTrue(sought > initial)
        assertEquals(sought, seen.last())

        player.selectTrack(TrackKind.Audio, TrackId(3))
        harness.run(300.milliseconds)
        val switched = player.audioClock().generation
        assertTrue(switched > sought, "audio switches must retire analysis without invalidating video")
        assertEquals(switched, seen.last())
        assertEquals(PlaybackStatus.Playing, player.state.value.status)

        player.stop()
        assertNull(player.audioClock().position)
        harness.openWithRenderer()
        player.play()
        harness.run(300.milliseconds)
        val reopened = player.audioClock().generation
        assertTrue(reopened > switched)
        assertEquals(reopened, seen.last())
        harness.close()
        assertNull(player.audioClock().position)
        assertTrue(player.audioClock().generation > reopened)
    }
}
