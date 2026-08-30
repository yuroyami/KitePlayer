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

    /**
     * P0-20. At a pitch-preserving speed the tempo stage holds up to two pitch periods it cannot
     * splice, because a splice needs the audio that comes after them and at the end of a stream
     * nothing does. The terminal state used to be decided without asking, so those frames met the
     * next reset instead of the device.
     *
     * Measured on this harness before and after the fix: 15,480 frames reached the device, then
     * 16,896. The 1,416 frames between them are the stage's lookahead, about 29 ms, and they are
     * the end of the media. The threshold below sits between the two readings and is derived from
     * the media rather than from either: at 1.5x, half a second of audio owes the device at least
     * `duration * rate / speed` frames.
     *
     * The assertion is the count, not the status. A run that ends is easy; a run that ends having
     * played everything is the fix.
     */
    @Test
    fun `a pitch-preserving speed still delivers the tail the tempo stage was holding`() = runTest {
        val script = MediaScript(durationUs = 500_000, hasVideo = false)
        val harness = CoreHarness(this, script = script, renderer = null)
        harness.open()
        harness.core.setSpeed(1.5)
        harness.core.play()
        harness.run(6.seconds)

        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "half a second at 1.5x must finish inside six seconds of virtual time",
        )
        val owed = (script.durationUs * script.sampleRate / 1_000_000L / 1.5).toLong()
        assertTrue(
            harness.sink.framesPlayed >= owed,
            "the device heard ${harness.sink.framesPlayed} frames where the media owes it $owed at " +
                "1.5x, so the tempo stage's lookahead was dropped rather than flushed",
        )
        harness.close()
    }

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
    // F-WRN1, the last of the four with no pin. FrameDropping was the same kind of defect as
    // AudioUnderrun above: a documented public type that nothing could be shown to raise. The
    // condition is a display too slow for the media, so the renderer here takes five frame periods
    // to present one, which is what makes the schedule run late enough to start dropping.
    @Test
    fun `a display that cannot keep up says FrameDropping out loud`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 6_000_000, videoFrameDurationUs = 40_000),
            renderer = RecordingRenderer(presentDuration = 80.milliseconds),
        )
        harness.openWithRenderer()
        harness.core.play()
        harness.run(5.seconds)

        val dropping = harness.core.warningHistory()
            .map { it.warning }
            .filterIsInstance<PlaybackWarning.FrameDropping>()
        assertTrue(
            dropping.isNotEmpty(),
            "a schedule dropping frames is worth a typed warning, dropped=" +
                harness.core.stats.value.droppedFramesLate + " history: " +
                harness.core.warningHistory().map { it.warning::class.simpleName }.toString(),
        )
        // The threshold is the claim, not just the type: the warning exists to separate a display
        // that is struggling from the odd single drop, so anything below five in an interval must
        // stay quiet. Reporting fewer would make the number in the warning a lie.
        assertTrue(
            dropping.all { it.droppedInLastSecond >= 5 },
            "every FrameDropping must carry at least the threshold it fired on, saw " +
                dropping.map { it.droppedInLastSecond }.toString(),
        )
        harness.close()
    }

    // A device asleep for a minute must not age the media. The clock is anchored to a system
    // timestamp, so anything that lets a paused interval count as elapsed media time makes the
    // position leap forward by however long the device was away, which past the end of the file
    // also means a spurious Ended.
    //
    // Measured 2026-08-30 rather than assumed: the behaviour is already correct, and what keeps it
    // correct is the freeze in MediaClock.pause. Worth stating because the obvious guess is wrong:
    // MediaClock.resume's re-anchor looks like the load-bearing part and is not. Deleting it
    // changes nothing here, because the audio ring's own anchor is refreshed by the device on the
    // way back up and overwrites it. Neutering the freeze instead moves this reading from 1.3
    // seconds to 1m 1.3s, which is the failure the pin is for.
    @Test
    fun `a minute of device sleep does not age the media`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(500.milliseconds)

        harness.core.pause()
        // Let the pause SETTLE before reading the baseline. A pause is not instantaneous: the ring
        // still holds a couple of hundred milliseconds of audio the device drains on its way down,
        // so the position keeps creeping for about that long and then stops. Reading the baseline
        // before it settles measures the drain, not the sleep.
        harness.run(1.seconds)
        val atPause = harness.core.progress.value.position

        harness.run(60.seconds)
        assertEquals(
            atPause,
            harness.core.progress.value.position,
            "a paused player must not advance while it is paused",
        )

        harness.core.play()
        harness.run(1.milliseconds)
        assertEquals(
            atPause,
            harness.core.progress.value.position,
            "the first reading after a resume must not have absorbed the sleep as elapsed media",
        )

        // And it really is playing afterwards, not merely frozen at a plausible number: a test that
        // only checked the position could pass on a player that never resumed at all.
        harness.run(1.seconds)
        val resumed = harness.core.progress.value.position
        assertTrue(
            resumed > atPause && resumed < atPause + 2.seconds,
            "after a second of play the position must have advanced about a second, was $resumed " +
                "against a pause at $atPause",
        )
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    // F-PLAY1 (owner report 2026-08-17): play at the end IS a restart, mpv's law. The intent
    // flag was already true after a natural end, so pressing play changed nothing and the
    // player sat in Ended for ever.
    @Test
    fun `play at Ended restarts from the beginning`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3.seconds)
        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)

        harness.core.play()
        harness.run(500.milliseconds)
        assertEquals(
            PlaybackStatus.Playing,
            harness.core.snapshots.value.status,
            "play at the end restarts playback",
        )
        val position = harness.core.progress.value.position
        assertTrue(
            position < 1.seconds,
            "the restart begins at the beginning, but the position reads $position",
        )
        harness.close()
    }
}
