package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds

/**
 * How long after [AudioPlayback.volume] changes the listener actually hears it.
 *
 * ### The report this came from
 *
 * Changing the volume in an app built on this engine does not change it straight away. Reported
 * 2026-08-31 from Synkplay, reproduced here as a measurement rather than a feeling.
 *
 * ### Why it is late, and why nothing else in the path is to blame
 *
 * The command reaches the engine promptly: the session loop selects on its command channel, so a
 * volume change wakes it rather than waiting for the next tick, and the value lands in an atomic the
 * feeder reads. The anti-click ramp is 5 ms. Neither is audible as a delay.
 *
 * The delay is WHERE the multiply happens. `AudioPipeline.process` applies the gain as the feeder
 * converts a buffer, and the result is then written to the ring. Every frame already in the ring was
 * multiplied by the OLD volume, and no later change can reach back into it. So the lag a listener
 * hears is however full the ring is, and the ring is deliberately deep:
 *
 *     max(sink.deviceBufferFrames * DEVICE_BUFFER_MULTIPLE, framesIn(200 ms))
 *
 * 200 ms is the floor. On Android the other term usually wins, because `deviceBufferFrames` is
 * AudioTrack's own buffer and the multiple is eight.
 *
 * ### What this test pins
 *
 * That the change is heard within one ramp plus one device period, which is what "instant" means for
 * audio. The lag it measures is the ring depth and nothing else, checked by varying that depth:
 * 42.7 ms of ring gave 46.4 ms of lag, 85.3 gave 89.0, and 170.7 gave 174.4. A constant 3.69 ms
 * apart every time, which is the ramp and the part-period the device already held. It does NOT pin a particular implementation. Applying the gain on the ring's READ side would
 * pass it; shrinking the ring would pass it only by trading away the underrun headroom the depth
 * exists for, and would still fail on any device whose buffer is large.
 */
class VolumeLatencyTest {

    private val format = AudioFormat(
        sampleRate = 48_000,
        channels = 2,
        sampleFormat = SampleFormat.F32,
        channelLayoutMask = null,
    )

    /** Everything the device was handed, in order, so the test can find where the gain changed. */
    private class CapturingBuffer(
        override val format: AudioFormat,
        capacityFrames: Int,
    ) : AudioSinkBuffer {
        val heard: MutableList<Float> = mutableListOf()
        private val scratch = FloatArray(capacityFrames * format.channels)

        fun takeFrames(frames: Int) {
            for (i in 0 until frames * format.channels) heard += scratch[i]
        }

        override fun writeInterleaved(
            source: FloatArray,
            sourceOffset: Int,
            destinationFrameOffset: Int,
            frames: Int,
        ) {
            source.copyInto(
                destination = scratch,
                destinationOffset = destinationFrameOffset * format.channels,
                startIndex = sourceOffset,
                endIndex = sourceOffset + frames * format.channels,
            )
        }

        override fun writePlane(
            channel: Int,
            source: FloatArray,
            sourceOffset: Int,
            destinationFrameOffset: Int,
            frames: Int,
        ) {
            for (frame in 0 until frames) {
                scratch[(destinationFrameOffset + frame) * format.channels + channel] =
                    source[sourceOffset + frame]
            }
        }

        override fun writeSilence(frameOffset: Int, frames: Int) {
            val base = frameOffset * format.channels
            for (i in 0 until frames * format.channels) scratch[base + i] = 0f
        }
    }

    /** A device the test pumps by hand, so the ring drains exactly when a case says to. */
    private class PullableSink(override val deviceBufferFrames: Int = 512) : AudioSink {
        private var render: AudioRenderCallback? = null
        var buffer: CapturingBuffer? = null
            private set

        override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
            this.render = render
            this.buffer = CapturingBuffer(request, deviceBufferFrames)
            return request
        }

        /** One device period. Returns the frames the engine supplied. */
        fun pumpOnce(): Int {
            val callback = render ?: return 0
            val destination = buffer ?: return 0
            val written = callback.onRender(destination, deviceBufferFrames, 0L)
            destination.takeFrames(deviceBufferFrames)
            return written
        }

        override suspend fun start() = Unit
        override suspend fun stop() = Unit
        override suspend fun drain() = Unit
        override suspend fun setPaused(paused: Boolean): Boolean = true
        override fun latencyNanos(): Long = 0
        override val latencyQuality: LatencyQuality = LatencyQuality.Estimated
        override val events: Flow<AudioSinkEvent> = emptyFlow()
        override fun close() = Unit
    }

    private fun framesToDuration(frames: Int): Duration =
        (frames.toLong() * 1_000_000L / format.sampleRate).microseconds

    @Test
    fun `a volume change is heard within a ramp rather than after the whole ring drains`() = runTest {
        val sink = PullableSink()
        val audio = AudioPlayback(sink, TestClock())
        audio.open(format)
        audio.play()

        // submitDecoded, not submit. submit writes STRAIGHT to the ring with no gain at all; the
        // pipeline, and therefore the gain stage, lives in submitDecoded, which then calls submit
        // with its processed output. A case built on submit measures nothing, because the volume it
        // sets is never applied to anything.
        //
        // Full scale, so the gain is the only thing that can change what comes out.
        val block = FloatArray(1_024 * format.channels) { 1f }
        // Fill until the ring is deep. `abort` is what keeps a full ring from parking this test
        // for ever: it is polled while a submit waits for room, and it is the same guard the feeder
        // uses. Stopping at a target depth rather than at "full" also keeps the case honest on a
        // device whose buffer makes the ring deeper than the 200 ms floor.
        repeat(64) {
            if (audio.buffered < FILL_TARGET) {
                audio.submitDecoded(
                    pts = null,
                    interleaved = block,
                    frames = 1_024,
                    sourceFormat = format,
                ) { audio.buffered >= FILL_TARGET }
            }
        }

        val queuedAtChange = audio.buffered
        assertTrue(
            queuedAtChange >= 100.milliseconds,
            "the ring must actually be deep for this measurement to mean anything, was $queuedAtChange",
        )

        audio.volume = QUIET

        // Steady-state playback: the device takes a period, the feeder tops the ring back up. That
        // topping up is the point. The gain is applied as a buffer is converted INTO the ring, so
        // only audio submitted after the change carries the new volume, and it queues up behind
        // everything already in there. A test that stopped submitting would never hear the new gain
        // at all, which is how this case failed the first time it was run.
        var framesPulled = 0
        var heardAt = -1
        val heard = sink.buffer!!.heard
        for (period in 0 until PUMP_LIMIT) {
            if (heardAt >= 0) break
            if (audio.buffered < FILL_TARGET) {
                audio.submitDecoded(
                    pts = null,
                    interleaved = block,
                    frames = 1_024,
                    sourceFormat = format,
                ) { audio.buffered >= FILL_TARGET }
            }
            val supplied = sink.pumpOnce()
            if (supplied <= 0) break
            while (framesPulled * format.channels < heard.size) {
                val sample = heard[framesPulled * format.channels]
                if (abs(sample - QUIET) < TOLERANCE) {
                    heardAt = framesPulled
                    break
                }
                framesPulled++
            }
        }

        assertTrue(heardAt >= 0, "the new volume was never heard at all within $PUMP_LIMIT device periods")

        val lag = framesToDuration(heardAt)
        // One ramp, plus the period already in the device's hands when the change landed.
        val allowed = GainStageRamp + framesToDuration(sink.deviceBufferFrames)
        assertTrue(
            lag <= allowed,
            "a volume change was heard $lag after it was made, and the budget is $allowed. " +
                "The ring held $queuedAtChange of already-gained audio at the moment of the change, " +
                "which is what the lag tracks: the gain is applied as the feeder writes INTO the ring, " +
                "so every frame already in it keeps the old volume. Applying the gain on the ring's " +
                "read side is what makes this pass without making the ring shallower.",
        )

        audio.close()
    }

    /**
     * A format change rebuilds the feeder's pipeline. It must not disturb what the listener hears.
     *
     * This replaces `a rebuild while muted stays silent through the swap`, which lived in
     * EngineAuditRegressionTest while the gain was the pipeline's last stage. Back then a rebuild
     * genuinely could break the property, because a fresh pipeline restarted at unity and played up
     * to a ramp of near-full-scale samples before walking back down; `GainStage.adoptRamp` existed
     * only to carry the ramp position across.
     *
     * With the gain on the ring's read side the property holds by construction, since the ring
     * outlives every pipeline rebuild. Checked rather than assumed, because "by construction" is a
     * claim about code that can be changed.
     */
    @Test
    fun `a pipeline rebuild does not disturb the ring's gain`() = runTest {
        val sink = PullableSink()
        val audio = AudioPlayback(sink, TestClock())
        audio.open(format)
        audio.play()
        audio.muted = true

        val block = FloatArray(1_024 * format.channels) { 1f }
        audio.submitDecoded(pts = null, interleaved = block, frames = 1_024, sourceFormat = format) { false }

        // A different SOURCE format is what makes the feeder rebuild its pipeline. The device format
        // is unchanged, so the ring and its gain are untouched.
        val otherSource = AudioFormat(
            sampleRate = 44_100,
            channels = format.channels,
            sampleFormat = SampleFormat.F32,
            channelLayoutMask = null,
        )
        audio.submitDecoded(
            pts = null,
            interleaved = block,
            frames = 1_024,
            sourceFormat = otherSource,
        ) { false }

        var peak = 0f
        repeat(8) {
            sink.pumpOnce()
        }
        for (sample in sink.buffer!!.heard) {
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
        }
        assertTrue(
            peak < 0.1f,
            "a muted path rebuilt mid-stream must stay silent, but the device was handed $peak",
        )
        audio.close()
    }

    private companion object {
        /** Quiet enough that no ramp step can be mistaken for it, loud enough not to be silence. */
        const val QUIET = 0.25f

        /** Float compare across a multiply chain; the ramp lands exactly but the mixer need not. */
        const val TOLERANCE = 0.01f

        /** [io.github.yuroyami.kiteplayer.internal.GainStage.DEFAULT_RAMP_DURATION]. */
        val GainStageRamp: Duration = 5.milliseconds

        /** Enough periods to cross a ring several times over, so a failure reports a real number. */
        const val PUMP_LIMIT = 512

        /** Deep enough to be a real ring, and reached long before the 64-block loop runs out. */
        val FILL_TARGET: Duration = 150.milliseconds
    }
}
