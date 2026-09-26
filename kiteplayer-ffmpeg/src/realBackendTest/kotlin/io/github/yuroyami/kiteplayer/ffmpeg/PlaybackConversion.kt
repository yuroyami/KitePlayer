package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.AudioPlayback
import io.github.yuroyami.kiteplayer.LatencyQuality
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import io.github.yuroyami.kiteplayer.spi.AudioRenderCallback
import io.github.yuroyami.kiteplayer.spi.AudioResamplerFactory
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkBuffer
import io.github.yuroyami.kiteplayer.spi.AudioSinkEvent
import io.github.yuroyami.kiteplayer.spi.SampleFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/** What the device heard, and the warnings the playback reported on the way. */
internal class Converted(val samples: FloatArray, val warnings: List<PlaybackWarning>)

/**
 * Plays [input] through [AudioPlayback], which runs the engine's audio pipeline, into a device at
 * [toRate]. A null [resampler] keeps the engine's own sinc.
 */
internal suspend fun convertThroughPlayback(
    input: FloatArray,
    channels: Int,
    fromRate: Int,
    toRate: Int,
    resampler: AudioResamplerFactory?,
    chunkFrames: Int = 1_024,
): Converted {
    val warnings = mutableListOf<PlaybackWarning>()
    val frames = input.size / channels
    val sink = PumpedSink(toRate)
    // Deep enough for the whole signal, so a submit never waits for a device that nobody pumps.
    val depth = (frames.toLong() * 1_000_000L / fromRate).microseconds + 1.seconds
    val audio = AudioPlayback(sink, bufferDuration = depth, onWarning = { warnings += it }, resampler = resampler)
    try {
        val format = AudioFormat(sampleRate = fromRate, channels = channels, sampleFormat = SampleFormat.F32)
        audio.open(format)
        audio.play()
        var at = 0
        while (at < frames) {
            val count = minOf(chunkFrames, frames - at)
            audio.submitDecoded(null, input.copyOfRange(at * channels, (at + count) * channels), count, format)
            at += count
        }
        audio.finishDecoded()
        sink.pumpAll()
    } finally {
        audio.close()
    }
    return Converted(sink.heard.toFloatArray(), warnings)
}

/** A device that accepts [rate] whatever it is asked for, pumped by hand, keeping what it heard. */
private class PumpedSink(private val rate: Int) : AudioSink {
    override val deviceBufferFrames: Int = 512
    private var render: AudioRenderCallback? = null
    private var buffer: HeardBuffer? = null
    val heard: MutableList<Float> = mutableListOf()

    override suspend fun open(request: AudioFormat, render: AudioRenderCallback): AudioFormat {
        val accepted = request.copy(sampleRate = rate)
        this.render = render
        buffer = HeardBuffer(accepted, deviceBufferFrames)
        return accepted
    }

    /** Device periods until the ring has nothing more to give. */
    fun pumpAll() {
        val callback = render ?: return
        val destination = buffer ?: return
        while (true) {
            val written = callback.onRender(destination, deviceBufferFrames, 0L)
            if (written <= 0) return
            for (i in 0 until written * destination.format.channels) heard += destination.values[i]
        }
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

private class HeardBuffer(override val format: AudioFormat, frames: Int) : AudioSinkBuffer {
    val values = FloatArray(frames * format.channels)

    override fun writeInterleaved(source: FloatArray, sourceOffset: Int, destinationFrameOffset: Int, frames: Int) {
        val end = sourceOffset + frames * format.channels
        source.copyInto(values, destinationFrameOffset * format.channels, sourceOffset, end)
    }

    override fun writeSilence(frameOffset: Int, frames: Int) {
        values.fill(0f, frameOffset * format.channels, (frameOffset + frames) * format.channels)
    }
}

/** [frames] frames of a sine at [hz], the same value on every channel. */
internal fun tone(frames: Int, rate: Int, hz: Double, channels: Int = 1, amplitude: Double = 1.0): FloatArray =
    FloatArray(frames * channels) { i -> (amplitude * sin(2.0 * PI * hz * (i / channels) / rate)).toFloat() }

internal fun meanSquare(samples: FloatArray, from: Int = 0, until: Int = samples.size): Double {
    var total = 0.0
    for (i in from until until) total += samples[i].toDouble() * samples[i]
    return total / (until - from)
}

/** How much of [samples] sits at [hz], as an amplitude: one bin of a discrete Fourier transform. */
internal fun amplitudeAt(samples: FloatArray, rate: Int, hz: Double, from: Int = 0, until: Int = samples.size): Double {
    var real = 0.0
    var imaginary = 0.0
    for (i in from until until) {
        val angle = 2.0 * PI * hz * (i - from) / rate
        real += samples[i] * cos(angle)
        imaginary += samples[i] * sin(angle)
    }
    return 2.0 * sqrt(real * real + imaginary * imaginary) / (until - from)
}
