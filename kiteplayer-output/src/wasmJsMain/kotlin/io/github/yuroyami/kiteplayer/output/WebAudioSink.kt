package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * The audible web sink, replacing the silence [SilentPacedAudioSink] kept time with.
 *
 * ### Why this is a feeder and not a device callback
 *
 * [AudioSink]'s contract is pull: the device asks the engine for samples on its own real-time
 * thread. An `AudioWorklet` IS that thread, and this sink still cannot use it, for a reason that is
 * structural rather than temporary. The worklet runs in its own realm with its own globals, the
 * engine's samples live in Kotlin/Wasm linear memory on the main thread, and without
 * `SharedArrayBuffer` those two memories cannot be the same memory. `SharedArrayBuffer` needs COOP
 * and COEP headers on whoever embeds the player, which is exactly the requirement 17.14 refused to
 * impose on the default artifact.
 *
 * So this is the other shape the contract names: a push device wrapped by one writer coroutine that
 * turns "the device has room" into a pull. The worklet holds a queue and plays it gaplessly; this
 * class keeps that queue full and calls [AudioRenderCallback.onRender] to fill it.
 *
 * ### The one surprise a caller must know about
 *
 * A browser starts every `AudioContext` suspended and only a real user gesture may resume it. Until
 * the page has had a click or a key, this sink hands audio to a device that plays nothing, its
 * queue fills, the feeder backs off, and because the engine's clock is anchored on consumed audio,
 * playback sits at position zero. That is correct behaviour and not a hang. Call [start] from a
 * gesture handler, or expect a play button to be part of the page.
 */
internal class WebAudioSink(
    private val device: WebAudioDevice,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
) : AudioSink {

    private var render: AudioRenderCallback? = null
    private var feeder: Job? = null
    private var block: InterleavedBlock? = null
    private var accepted: AudioFormat? = null
    private var reportedUnderrunFrames = 0

    private val _events = MutableSharedFlow<AudioSinkEvent>(extraBufferCapacity = 8)
    override val events: Flow<AudioSinkEvent> = _events

    /**
     * The hardware picks the rate and the channel count, and the engine resamples to whatever comes
     * back. A sink never converts, so nothing here touches the samples.
     */
    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
        val format = AudioFormat(
            sampleRate = device.sampleRate,
            channels = device.channels,
            sampleFormat = SampleFormat.F32,
        )
        this.render = render
        this.accepted = format
        this.block = InterleavedBlock(format, deviceBufferFrames)
        return format
    }

    override suspend fun start() {
        if (feeder != null) return
        device.resume()
        feeder = scope.launch { feedLoop() }
    }

    /** Seek: everything unplayed is dropped, because it belongs to the position being left. */
    override suspend fun stop() {
        feeder?.cancelAndJoin()
        feeder = null
        device.flush()
    }

    override suspend fun setPaused(paused: Boolean): Boolean {
        if (paused) {
            feeder?.cancelAndJoin()
            feeder = null
            device.suspendPlayback()
        } else {
            start()
        }
        return true
    }

    /**
     * End of media: stop filling, let what is queued play out, then stop.
     *
     * Bounded rather than open-ended. A context the user never resumed will never drain, and a
     * drain that waits forever on a gesture that is not coming would hang the caller's teardown.
     */
    override suspend fun drain() {
        feeder?.cancelAndJoin()
        feeder = null
        val format = accepted ?: return
        val queueNanos = highWaterFrames.toLong() * 1_000_000_000L / format.sampleRate
        val deadline = clock.nanos() + queueNanos + 500_000_000L
        while (device.queuedFrames() > 0 && clock.nanos() < deadline) {
            delay(pollMillis)
        }
        device.flush()
    }

    override fun close() {
        feeder?.cancel()
        feeder = null
        render = null
        block = null
        accepted = null
        device.close()
    }

    /**
     * Refills the device's queue up to [highWaterFrames], then waits.
     *
     * The wait is what makes this a feeder rather than a spin: a loop with no wait would render the
     * whole file into the worklet's queue in one turn, and every buffering and sync decision above
     * would be made against a queue that is never short.
     */
    private suspend fun feedLoop() {
        val format = accepted ?: return
        val callback = render ?: return
        val buffer = block ?: return
        val frames = deviceBufferFrames
        while (coroutineContext.isActive) {
            while (coroutineContext.isActive && device.queuedFrames() + frames <= highWaterFrames) {
                val pending = device.queuedFrames()
                val written = callback.onRender(buffer, frames, deadlineFor(pending, frames, format))
                // Short block means the rest is silence, and writing it is the sink's obligation:
                // the staging array is reused, so an unwritten tail would replay the last block.
                val done = written.coerceIn(0, frames)
                if (done < frames) buffer.writeSilence(done, frames - done)
                device.enqueue(buffer.samples, frames)
            }
            publishUnderruns()
            delay(pollMillis)
        }
    }

    /**
     * When the LAST frame of this block becomes audible, on the engine's own clock.
     *
     * Everything ahead of it has to play first, so that is [pending] frames plus this block, plus
     * whatever the platform says still sits between handing a frame over and hearing it.
     *
     * Its accuracy is bounded by how stale [WebAudioDevice.queuedFrames] is, which is one worklet
     * report interval. The real device reports every four render quanta, so the error is at most
     * about 10 ms at 48 kHz and always in the direction of claiming audio is further away than it
     * is. That is the safe direction: it never asks the engine to believe a frame is already past.
     */
    private fun deadlineFor(pending: Int, frames: Int, format: AudioFormat): Long {
        val aheadFrames = (pending + frames).toLong()
        val aheadNanos = aheadFrames * 1_000_000_000L / format.sampleRate
        return clock.nanos() + aheadNanos + outputLatencyNanos()
    }

    private fun outputLatencyNanos(): Long {
        val seconds = device.outputLatencySeconds() ?: return 0
        return (seconds * 1_000_000_000.0).toLong()
    }

    /** The device counts silence it invented; this turns growth in that count into one event. */
    private fun publishUnderruns() {
        val total = device.underrunFrames()
        if (total <= reportedUnderrunFrames) return
        val added = total - reportedUnderrunFrames
        reportedUnderrunFrames = total
        _events.tryEmit(AudioSinkEvent.Underrun("the web audio queue ran dry for $added frames"))
    }

    override val deviceBufferFrames: Int = WEB_BLOCK_FRAMES

    /**
     * How much audio is handed over but not yet heard.
     *
     * Honest rather than small, as the contract asks: it counts the worklet's own queue as well as
     * the platform's figure, because a queue this class filled is latency this class created.
     */
    override fun latencyNanos(): Long {
        val format = accepted ?: return 0
        val queuedNanos = device.queuedFrames().toLong() * 1_000_000_000L / format.sampleRate
        return queuedNanos + outputLatencyNanos()
    }

    /**
     * Estimated, never Exact, and the browser is the reason.
     *
     * `AudioContext.outputLatency` is a real measurement where it exists, but it moves between
     * callbacks and some engines only offer `baseLatency`, which describes the graph rather than the
     * device. With no figure at all there is nothing to filter and the answer is Unreliable.
     */
    override val latencyQuality: LatencyQuality
        get() = if (device.outputLatencySeconds() == null) LatencyQuality.Unreliable else LatencyQuality.Estimated

    private companion object {
        /**
         * Four blocks, about 85 ms at 48 kHz.
         *
         * Deep enough that the main thread can be blocked by a layout or a decode without the queue
         * running dry, shallow enough that a seek throws away little and a pause responds quickly.
         */
        const val highWaterFrames = 4096

        /** Half a block. Refills land well before the queue is short. */
        const val pollMillis = 10L
    }
}

/**
 * One block, eight `AudioWorklet` quanta of 128 frames, which is the unit the worklet renders.
 *
 * Shared rather than written twice: it sizes both the sink's staging block and the JS `Float32Array`
 * the device stages into, and two constants that must agree is one constant waiting to be edited
 * alone. A mismatch would not fail to compile, it would write past the end of the JS array.
 */
internal const val WEB_BLOCK_FRAMES: Int = 1024

/**
 * The audio device seam, so the sink's policy is testable without a browser.
 *
 * The feeder's decisions are arithmetic on queue depth and time, and asserting them against a real
 * `AudioContext` would need a browser, a user gesture and a stopwatch. Handed a fake, the same code
 * is exact. [SilentPacedAudioSink] splits scope and clock out for the same reason.
 */
internal interface WebAudioDevice {
    /** What the hardware imposes. The engine resamples to it; a sink never converts. */
    val sampleRate: Int

    /** What the destination will actually carry, which may be fewer than were asked for. */
    val channels: Int

    /** Frames handed over that have not yet left the queue. */
    fun queuedFrames(): Int

    /** Frames of silence the device invented because the queue was dry. Monotonic. */
    fun underrunFrames(): Int

    /** Seconds between handing a frame over and hearing it, or null when the platform has no figure. */
    fun outputLatencySeconds(): Double?

    /** Hands over one block: [frames] times [channels] interleaved floats from index 0. */
    fun enqueue(samples: FloatArray, frames: Int)

    /** Drops everything unplayed. */
    fun flush()

    suspend fun resume()

    suspend fun suspendPlayback()

    fun close()
}

/**
 * One block of interleaved floats, reused every render.
 *
 * Bounds-checked rather than trusting its caller, exactly like the discard buffer it replaces: the
 * render callback is engine code, and on the one platform that used to be silent a bad offset would
 * have been invisible. Here it would be audible, which is a better outcome but still worth catching
 * at the write.
 */
internal class InterleavedBlock(
    override val format: AudioFormat,
    private val capacityFrames: Int,
) : AudioSinkBuffer {

    val samples: FloatArray = FloatArray(capacityFrames * format.channels)

    override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
        check(destinationFrameOffset + frames <= capacityFrames) {
            "interleaved write of $frames frames at $destinationFrameOffset exceeds $capacityFrames"
        }
        check(sourceOffset + frames * format.channels <= source.size) {
            "interleaved write reads past the end of its source"
        }
        source.copyInto(
            destination = samples,
            destinationOffset = destinationFrameOffset * format.channels,
            startIndex = sourceOffset,
            endIndex = sourceOffset + frames * format.channels,
        )
    }

    override fun writePlane(channel: Int, source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
        check(channel in 0 until format.channels) { "plane $channel is outside ${format.channels} channels" }
        check(destinationFrameOffset + frames <= capacityFrames) {
            "plane write of $frames frames at $destinationFrameOffset exceeds $capacityFrames"
        }
        check(sourceOffset + frames <= source.size) { "plane write reads past the end of its source" }
        var destination = destinationFrameOffset * format.channels + channel
        for (i in 0 until frames) {
            samples[destination] = source[sourceOffset + i]
            destination += format.channels
        }
    }

    override fun writeSilence(frameOffset: Int, frames: Int) {
        check(frameOffset + frames <= capacityFrames) {
            "silence of $frames frames at $frameOffset exceeds $capacityFrames"
        }
        samples.fill(0f, frameOffset * format.channels, (frameOffset + frames) * format.channels)
    }
}
