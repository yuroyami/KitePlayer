package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.coroutines.runBlocking
import platform.CoreAudio.AudioGetCurrentHostTime
import platform.CoreAudioTypes.AudioBufferList
import platform.CoreAudioTypes.AudioTimeStamp
import platform.CoreAudioTypes.kAudioTimeStampHostTimeValid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the sink's real-time path over memory the test owns.
 *
 * The device decides when it calls, how much it wants and what its timestamp claims, so the cases
 * that matter most cannot be asked for on demand: a callback that fills less than it was handed, no
 * callback at all, a timestamp whose host time CoreAudio marks meaningless. Passing the sink a buffer
 * list of our own turns each one into a plain assertion. The device tests in `CoreAudioSinkTest` still
 * cover the path CoreAudio itself takes, and the sink is opened here but never started, so nothing but
 * this test calls the render path.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreAudioSinkRealTimeTest {

    private val format = AudioFormat(sampleRate = 48_000, channels = 2, sampleFormat = SampleFormat.F32)

    /** A callback that behaves: fills everything it was given and says so. */
    private val fillsEverything = AudioRenderCallback { destination, frames, _ ->
        destination.writeSilence(0, frames)
        frames
    }

    private fun openIdleSink(): CoreAudioSink {
        val sink = CoreAudioSink()
        runBlocking { sink.open(format, fillsEverything) }
        return sink
    }

    /**
     * One turn of the real-time path, and what it left behind.
     *
     * The scratch memory starts at [prefill] rather than zero, so silence in the result is something
     * the sink wrote and not something that was already there.
     */
    private fun renderOnce(
        sink: CoreAudioSink,
        frames: Int,
        callback: AudioRenderCallback?,
        hostTimeValid: Boolean = true,
        prefill: Float = 1f,
    ): FloatArray {
        val samples = frames * format.channels
        return memScoped {
            val storage = allocArray<FloatVar>(samples)
            for (i in 0 until samples) storage[i] = prefill

            val bufferList = alloc<AudioBufferList>()
            bufferList.mNumberBuffers = 1u
            bufferList.mBuffers[0].mNumberChannels = format.channels.toUInt()
            bufferList.mBuffers[0].mDataByteSize = (samples * 4).toUInt()
            bufferList.mBuffers[0].mData = storage

            val timeStamp = alloc<AudioTimeStamp>()
            timeStamp.mHostTime = AudioGetCurrentHostTime()
            timeStamp.mFlags = if (hostTimeValid) kAudioTimeStampHostTimeValid else 0u

            sink.fillDeviceBuffer(callback, timeStamp, frames, bufferList)
            FloatArray(samples) { storage[it] }
        }
    }

    private fun assertAllSilent(samples: FloatArray, from: Int, until: Int, what: String) {
        for (i in from until until) {
            assertEquals(0f, samples[i], "$what: sample $i of $until was ${samples[i]}")
        }
    }

    @Test
    fun `a device buffer with no callback to fill it comes back silent`() {
        // Between close clearing the callback and the device noticing, the buffer would otherwise go
        // back holding whatever was in it, which is a burst of noise. The sink is the last line here.
        val sink = openIdleSink()
        try {
            val filled = renderOnce(sink, frames = 64, callback = null)
            assertAllSilent(filled, 0, filled.size, "a callback-less render must zero the whole buffer")
        } finally {
            sink.close()
        }
    }

    @Test
    fun `a short render has its remainder zero filled`() {
        // A ring near the end of a file writes what it has and returns that count. The frames it did
        // not write are the sink's problem, not the device's.
        val sink = openIdleSink()
        try {
            val frames = 64
            val supplied = 16
            val tone = FloatArray(supplied * format.channels) { 0.5f }

            val filled = renderOnce(
                sink,
                frames = frames,
                callback = AudioRenderCallback { destination, _, _ ->
                    destination.writeInterleaved(tone, 0, 0, supplied)
                    supplied
                },
            )

            for (i in 0 until supplied * format.channels) {
                assertEquals(0.5f, filled[i], "the frames the callback wrote must survive: sample $i")
            }
            assertAllSilent(
                filled,
                supplied * format.channels,
                filled.size,
                "the frames the callback did not write must be silence",
            )
        } finally {
            sink.close()
        }
    }

    @Test
    fun `a render longer than the device asked for cannot leave the buffer`() {
        // Writing past a buffer CoreAudio owns is memory corruption rather than a wrong sample, so the
        // wrapper clamps to the frame count the device gave it. The guard sample after the end proves
        // the clamp held.
        val sink = openIdleSink()
        try {
            val frames = 32
            val tooMuch = FloatArray(frames * 2 * format.channels) { 0.25f }

            val filled = renderOnce(
                sink,
                frames = frames,
                callback = AudioRenderCallback { destination, _, _ ->
                    destination.writeInterleaved(tooMuch, 0, 0, frames * 2)
                    destination.writeInterleaved(tooMuch, 0, frames, frames)
                    destination.writeSilence(frames, frames)
                    frames
                },
            )

            assertEquals(frames * format.channels, filled.size)
            for (i in filled.indices) {
                assertEquals(0.25f, filled[i], "the clamp must still write everything that does fit: sample $i")
            }
        } finally {
            sink.close()
        }
    }

    @Test
    fun `an invalid host time falls back to the engine clock`() {
        // CoreAudio says which fields of its timestamp mean anything. Reading a host time it did not
        // flag valid would anchor the audio clock to a number with no meaning.
        val sink = openIdleSink()
        try {
            val frames = 256
            val bufferNanos = frames.toLong() * 1_000_000_000L / format.sampleRate
            val estimatesBefore = sink.estimatedAnchors
            var deadline = 0L

            val before = AppleHostClock.nanos()
            renderOnce(
                sink,
                frames = frames,
                hostTimeValid = false,
                callback = AudioRenderCallback { destination, count, deadlineNanos ->
                    deadline = deadlineNanos
                    destination.writeSilence(0, count)
                    count
                },
            )
            val after = AppleHostClock.nanos()

            assertEquals(estimatesBefore + 1, sink.estimatedAnchors, "an estimated anchor must be counted")
            assertTrue(
                deadline in (before + bufferNanos)..(after + bufferNanos),
                "the fallback anchor is now plus this buffer: expected ${before + bufferNanos} .. " +
                    "${after + bufferNanos}, was $deadline",
            )
        } finally {
            sink.close()
        }
    }

    @Test
    fun `a valid host time is used as it stands`() {
        val sink = openIdleSink()
        try {
            val frames = 256
            val bufferNanos = frames.toLong() * 1_000_000_000L / format.sampleRate
            val estimatesBefore = sink.estimatedAnchors
            var deadline = 0L

            val before = AppleHostClock.nanos()
            renderOnce(
                sink,
                frames = frames,
                callback = AudioRenderCallback { destination, count, deadlineNanos ->
                    deadline = deadlineNanos
                    destination.writeSilence(0, count)
                    count
                },
            )
            val after = AppleHostClock.nanos()

            assertEquals(estimatesBefore, sink.estimatedAnchors, "a valid host time needs no estimate")
            assertTrue(
                deadline in (before + bufferNanos)..(after + bufferNanos),
                "the host time taken inside this call plus the buffer must land inside the call: expected " +
                    "${before + bufferNanos} .. ${after + bufferNanos}, was $deadline",
            )
        } finally {
            sink.close()
        }
    }
}
