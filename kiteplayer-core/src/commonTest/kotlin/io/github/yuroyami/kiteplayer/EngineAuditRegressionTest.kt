@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.AudioPipeline
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regressions from the 2026-08-17 code audit (KPKMP 17.11.b). Each test was proven RED against
 * the defect it pins before the fix landed, which is what makes it a pin and not a description.
 */
class EngineAuditRegressionTest {

    // F-LOOP1: LoopMode.One at the end of an unseekable source must not issue a seek. The A-B
    // branch already refuses; the plain repeat branch was the one seek path with no guard.
    @Test
    fun `looping an unseekable source never seeks it and leaves the player Ended`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(seekable = false, durationUs = 1_000_000))
        harness.openWithRenderer()
        harness.core.setLoop(LoopMode.One)
        harness.core.play()
        harness.run(4.seconds)

        assertEquals(0, harness.source.seeks, "an unseekable source must never be asked to seek")
        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "the repeat that cannot happen leaves the player Ended, not Failed and not respawning",
        )
        assertTrue(
            harness.events.none { it is PlayerEvent.Failed },
            "a loop the source cannot honour is a warning, never a failure",
        )
        harness.close()
    }

    // F-SEEK1: a seek queued against the previous media must not run against the newly opened
    // one. runOpen resets the whole seek machine except the request itself.
    @Test
    fun `a seek queued before an open never runs against the new media`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(5.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)

        // The user drags the bar on the finished episode and taps next in the same instant: the
        // SeekLater command lands in the queue immediately ahead of the Open.
        harness.core.seekLater(Pts(2_000_000), SeekMode.Precise)
        harness.open("scripted://episode2")
        harness.run(1.seconds)

        val position = harness.core.progress.value.position
        assertTrue(
            position < 1.seconds,
            "the fresh media opens at its beginning; the stale request must be superseded, " +
                "but the position reads $position",
        )
        harness.close()
    }

    // F-EOS1: the wait for the audio ring to empty at end of stream must be bounded. A device
    // that stopped pulling used to park the player one poll before Ended for ever.
    @Test
    fun `a device that stops pulling cannot hold off Ended for ever`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(hasVideo = false, durationUs = 4_000_000),
            // A fast progress pulse: the stop below aims at a 150 ms window near the end, and the
            // default 200 ms pulse quantizes right past it.
            config = PlayerConfig(progressInterval = 10.milliseconds),
            renderer = null,
        )
        harness.open()
        harness.core.play()
        // Walk to just before the end BY POSITION, not by wall time: the open itself consumes
        // virtual time. At 3.83 s the feeder has already handed the ring the whole tail (the
        // ring holds 200 ms), the decoders are drained, and the device still owes ~170 ms.
        while (harness.core.progress.value.position < 3830.milliseconds) {
            harness.run(5.milliseconds)
        }
        // The device dies with the last buffers still in the ring.
        harness.stopDevice()
        harness.run(15.seconds)

        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "the drain wait must be bounded by the same deadline the drain itself has",
        )
        harness.close()
    }

    // F-SP1: a refused speed change must leave no trace. The old order wrote the rate into both
    // pipelines first and decided to refuse afterwards, so a paused video clock kept the rate.
    @Test
    fun `a refused speed change leaves the video clock at the old rate`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(hasAudio = false, seekable = false, durationUs = 4_000_000))
        harness.openWithRenderer()

        assertFailsWith<UnsupportedOperationException> { harness.core.setSpeed(2.0) }

        harness.core.play()
        harness.run(2.seconds)
        val position = harness.core.progress.value.position
        assertTrue(
            position <= 2400.milliseconds,
            "after two wall seconds at a refused 2x the position must still obey 1x, was $position",
        )
        harness.close()
    }

    // F-CFG1: the config must refuse what the pipeline it configures refuses. FrameQueue needs
    // two slots to time a frame, so a policy of one slot was an open() crash wearing a valid coat.
    @Test
    fun `a one slot video frame queue is refused at configuration`() {
        assertFailsWith<IllegalArgumentException> { BufferPolicy(videoFrameQueue = 1) }
    }

    // F-MIX1: a pass-through with UNEQUAL channel counts must still run the mixer's frame-wise
    // restride. The SOL-P2 alias keyed on isPassThrough and handed a 3-channel interleave to a
    // stereo consumer untouched: every sample on the wrong speaker at the wrong time.
    @Test
    fun `an unmodelled channel layout is restrided to the target count not aliased`() {
        // FL or FR or FC is mask 0x7: three channels, and not one of the modelled layouts, so
        // the mixer's documented fallback is first-channels pass-through PER FRAME.
        val source = AudioFormat(48_000, 3, SampleFormat.F32, channelLayoutMask = 0x7L)
        val target = AudioFormat(48_000, 2, SampleFormat.F32)
        val pipeline = AudioPipeline(source, target, onWarning = {})

        // Frame n carries (n, n, n) so an aliased 3-stride read is distinguishable from a
        // restrided 2-of-3 copy by value alone.
        val frames = 4
        val input = FloatArray(frames * 3) { (it / 3).toFloat() }
        val produced = pipeline.process(input, frames)
        assertEquals(frames, produced)
        val out = pipeline.output
        for (frame in 0 until frames) {
            assertEquals(frame.toFloat(), out[frame * 2], "frame $frame left channel")
            assertEquals(frame.toFloat(), out[frame * 2 + 1], "frame $frame right channel")
        }
        assertFalse(
            pipeline.output === input,
            "unequal channel counts can never alias the caller's buffer",
        )
    }

    @Test
    fun `a matching channel pair still aliases and pays no copy`() {
        val format = AudioFormat(48_000, 2, SampleFormat.F32)
        val pipeline = AudioPipeline(format, format, onWarning = {})
        val input = FloatArray(8) { 0.25f }
        pipeline.process(input, 4)
        assertTrue(pipeline.output === input, "SOL-P2's zero-copy contract holds for the identity pair")
    }

    // F-GAIN1: a pipeline rebuilt mid-stream must inherit the applied gain, not restart at
    // unity. Rebuilding while muted used to play up to one ramp of near-full-scale samples.
    @Test
    fun `a rebuild while muted stays silent through the swap`() {
        val format = AudioFormat(48_000, 2, SampleFormat.F32)
        val pipeline = AudioPipeline(format, format, onWarning = {})
        pipeline.muted = true

        // Long past the ramp: the applied gain has genuinely reached silence.
        val silenceRun = FloatArray(48_000 * 2) { 1f }
        pipeline.process(silenceRun, 48_000)

        val rebuilt = pipeline.rebuiltFor(AudioFormat(44_100, 2, SampleFormat.F32))
        val loud = FloatArray(256 * 2) { 1f }
        val produced = rebuilt.process(loud, 256)
        assertTrue(produced > 0)
        var peak = 0f
        for (i in 0 until produced * 2) {
            val v = kotlin.math.abs(rebuilt.output[i])
            if (v > peak) peak = v
        }
        assertTrue(
            peak < 0.1f,
            "a muted pipeline rebuilt mid-stream must stay silent, but the first buffer peaked at $peak",
        )
    }
    // F-WRN1: AudioUnderrun was a documented public type wired to nothing. A demuxer slower
    // than real time starves the ring, and the starvation must reach the warning history.
    @Test
    fun `a starved ring says AudioUnderrun out loud`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(hasVideo = false, durationUs = 3_000_000, readDelayUs = 60_000),
            renderer = null,
        )
        harness.open()
        harness.core.play()
        harness.run(3.seconds)
        assertTrue(
            harness.core.warningHistory().any { it.warning is PlaybackWarning.AudioUnderrun },
            "a device running dry is worth a typed warning, history: " +
                harness.core.warningHistory().map { it.warning::class.simpleName }.toString(),
        )
        harness.close()
    }
}
