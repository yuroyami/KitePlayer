package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.audio.LoudnessMeter
import io.github.yuroyami.kiteplayer.audio.LoudnessResult

/** Measurements over a whole file, decoded here rather than played. */
public object AudioAnalysis {

    /**
     * Decodes the primary audio stream and measures its integrated loudness to ITU-R BS.1770-4
     * with [LoudnessMeter], which is what ReplayGain and EBU R128 both want. Reads the whole
     * file: seconds on a long album, so call it off the main thread. A file with no audio that
     * passes the gates answers negative infinity, the way the meter does.
     *
     * @throws IllegalArgumentException for an item with no audio stream
     * @throws io.github.yuroyami.kiteffmpeg.FFmpegException when the open or the decode fails
     */
    public suspend fun measureLoudness(item: MediaItem): LoudnessResult {
        openSource(item).use { source ->
            val stream = source.primaryAudio
                ?: throw IllegalArgumentException("${item.label} has no audio stream to measure")
            var meter: LoudnessMeter? = null
            source.decodedFrames(stream).collect { frame ->
                frame.use { decoded ->
                    val info = decoded.info
                    if (info.sampleCount == 0) return@use
                    val active = meter ?: LoudnessMeter(info.sampleRate, info.channelCount).also { meter = it }
                    active.feed(AudioSamples.toFloatInterleaved(decoded), info.sampleCount)
                }
            }
            return (meter ?: LoudnessMeter(48_000, 1)).result()
        }
    }
}
