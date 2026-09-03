package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaType
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.TrackId
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * A file drawn as [peaks] and [rms], one entry per bucket of [bucketDuration], both in 0..1 and
 * mono-mixed. A bucket nothing was decoded into reads zero in both. Two waveforms are equal only
 * when they are the same object, since the arrays are arrays.
 */
public data class Waveform(
    val bucketDuration: Duration,
    val peaks: FloatArray,
    val rms: FloatArray,
)

/** Audio apps draw the file. This decodes it into the shape they draw. */
public object Waveforms {

    /**
     * Decodes the primary audio stream, or [stream], into [buckets] equal time slices of peak
     * and RMS, the channels averaged to mono first. Reads the whole stream; seconds on a long
     * album, so call it off the main thread. Samples are placed by counting them from the start
     * at the stream's rate, so a file with gaps in its timeline draws them closed up.
     *
     * @throws IllegalArgumentException for [buckets] below one, an item with no such audio
     *         stream, or a stream whose duration is unknown and so cannot be divided
     * @throws io.github.yuroyami.kiteffmpeg.FFmpegException when the open or the decode fails
     */
    public suspend fun of(item: MediaItem, buckets: Int = 1000, stream: TrackId? = null): Waveform {
        require(buckets > 0) { "buckets must be positive, was $buckets" }
        openSource(item).use { source ->
            val chosen = if (stream == null) {
                source.primaryAudio
            } else {
                source.streams.firstOrNull { it.index == stream.value && it.type == MediaType.Audio }
            } ?: throw IllegalArgumentException(
                if (stream == null) "${item.label} has no audio stream to draw" else "${item.label} has no audio stream $stream",
            )
            val durationMicros = chosen.durationMicros?.takeIf { it > 0 } ?: source.durationMicros?.takeIf { it > 0 }
                ?: throw IllegalArgumentException("${item.label} has no known duration to divide into buckets")

            val peaks = FloatArray(buckets)
            val squares = DoubleArray(buckets)
            val counts = IntArray(buckets)
            var samplesSoFar = 0L
            source.decodedFrames(chosen).collect { frame ->
                frame.use { decoded ->
                    val info = decoded.info
                    if (info.sampleCount == 0 || info.sampleRate <= 0) return@use
                    val floats = AudioSamples.toFloatInterleaved(decoded)
                    val channels = info.channelCount
                    for (sample in 0 until info.sampleCount) {
                        var mono = 0f
                        for (channel in 0 until channels) mono += floats[sample * channels + channel]
                        mono /= channels
                        val micros = (samplesSoFar + sample) * 1_000_000L / info.sampleRate
                        val bucket = (micros * buckets / durationMicros).toInt().coerceIn(0, buckets - 1)
                        val magnitude = abs(mono)
                        if (magnitude > peaks[bucket]) peaks[bucket] = magnitude
                        squares[bucket] += mono.toDouble() * mono
                        counts[bucket]++
                    }
                    samplesSoFar += info.sampleCount
                }
            }
            val rms = FloatArray(buckets) { if (counts[it] == 0) 0f else sqrt(squares[it] / counts[it]).toFloat() }
            return Waveform((durationMicros / buckets).microseconds, peaks, rms)
        }
    }
}
