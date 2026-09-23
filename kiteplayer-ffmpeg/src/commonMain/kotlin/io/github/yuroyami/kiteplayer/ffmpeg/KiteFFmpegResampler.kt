package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FilterGraph
import io.github.yuroyami.kiteffmpeg.Frame
import io.github.yuroyami.kiteffmpeg.Rational
import io.github.yuroyami.kiteffmpeg.SampleFormat
import io.github.yuroyami.kiteplayer.spi.AudioResampler
import io.github.yuroyami.kiteplayer.spi.AudioResamplerFactory

/**
 * Converts sample rates with FFmpeg's libswresample, through the `aresample` filter of the
 * KiteFFmpeg filter graph. Set it in `AudioConfig.resampler`.
 *
 * Android, the JVM and Kotlin/Native only. The web build of KiteFFmpeg has no filter graph, so
 * there [create] throws. The engine then keeps its own resampler and reports
 * `PlaybackWarning.ResamplerUnavailable`.
 */
public class KiteFFmpegResampler : AudioResamplerFactory {
    override fun create(inputRate: Int, outputRate: Int, channels: Int): AudioResampler =
        FilterGraphResampler(inputRate, outputRate, channels)
}

/**
 * One `aresample` graph for one pair of rates.
 *
 * Samples cross into the graph as packed 32-bit floats in little-endian byte order, which is the
 * native order on every target that KiteFFmpeg ships. A graph cannot take input after a flush, so
 * a flush or a reset drops it, and the next [process] builds a new one.
 */
internal class FilterGraphResampler(
    private val inputRate: Int,
    private val outputRate: Int,
    private val channels: Int,
) : AudioResampler {

    init {
        require(inputRate > 0 && outputRate > 0) { "rates must be positive, were $inputRate and $outputRate" }
        require(channels in 1..MAX_CHANNELS) { "a KiteFFmpeg frame carries 1 to $MAX_CHANNELS channels, not $channels" }
    }

    private var graph: FilterGraph? = build()

    /** Converted samples not handed out yet, interleaved. A later call hands them out first. */
    private var pending = FloatArray(0)
    private var pendingValues = 0

    private var bytes = ByteArray(0)

    private fun build(): FilterGraph = FilterGraph.buildAudio(
        description = "aresample=$outputRate",
        sampleRate = inputRate,
        sampleFormat = SampleFormat.Flt,
        channels = channels,
        timeBase = Rational(1, inputRate),
        outputSampleRate = outputRate,
        outputSampleFormat = SampleFormat.Flt,
        outputChannels = channels,
    )

    override fun outputCapacity(inputFrames: Int): Int {
        val converted = (maxOf(inputFrames, 0) + HELD_INPUT_FRAMES).toLong() * outputRate / inputRate
        return pendingValues / channels + converted.toInt() + 2
    }

    override fun process(input: FloatArray, frames: Int, output: FloatArray): Int {
        if (frames <= 0) return 0
        val active = graph ?: build().also { graph = it }
        val values = frames * channels
        if (bytes.size < values * 4) bytes = ByteArray(values * 4)
        for (i in 0 until values) {
            val bits = input[i].toRawBits()
            val at = i * 4
            bytes[at] = bits.toByte()
            bytes[at + 1] = (bits ushr 8).toByte()
            bytes[at + 2] = (bits ushr 16).toByte()
            bytes[at + 3] = (bits ushr 24).toByte()
        }
        // The graph closes the frame it is fed.
        active.feedInput(0, Frame.ofAudio(bytes, frames, inputRate, channels, SampleFormat.Flt), ::take)
        return handOut(output)
    }

    override fun flush(output: FloatArray): Int {
        val active = graph
        if (active != null) {
            graph = null
            try {
                active.flushInput(0, ::take)
            } finally {
                active.close()
            }
        }
        return handOut(output)
    }

    override fun reset() {
        graph?.close()
        graph = null
        pendingValues = 0
    }

    override fun close() {
        graph?.close()
        graph = null
    }

    /** Appends one converted frame to [pending]. The frame is valid only inside the callback. */
    private fun take(frame: Frame) {
        val converted = frame.copyPlanesToByteArray()
        val values = converted.size / 4
        if (pending.size < pendingValues + values) {
            pending = pending.copyOf(maxOf(pendingValues + values, pending.size * 2))
        }
        for (i in 0 until values) {
            val at = i * 4
            val bits = (converted[at].toInt() and 0xFF) or
                ((converted[at + 1].toInt() and 0xFF) shl 8) or
                ((converted[at + 2].toInt() and 0xFF) shl 16) or
                (converted[at + 3].toInt() shl 24)
            pending[pendingValues + i] = Float.fromBits(bits)
        }
        pendingValues += values
    }

    /** Copies as much of [pending] as [output] holds, and keeps the rest for the next call. */
    private fun handOut(output: FloatArray): Int {
        val values = minOf(pendingValues, output.size / channels * channels)
        pending.copyInto(output, 0, 0, values)
        pending.copyInto(pending, 0, values, pendingValues)
        pendingValues -= values
        return values / channels
    }

    private companion object {
        /** The most channels a KiteFFmpeg audio frame carries. */
        const val MAX_CHANNELS = 8

        /**
         * Input frames the graph may hold between calls, rounded up generously. The default
         * libswresample filter holds 16. Too small a figure costs latency, never samples, because
         * [handOut] keeps what does not fit.
         */
        const val HELD_INPUT_FRAMES = 256
    }
}
