package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.CorruptPackets
import io.github.yuroyami.kiteplayer.DemuxPolicy
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.ProbeDepth

/** FFmpeg refuses a smaller `probesize`, and the open then fails as if the file were unreadable. */
private const val SMALLEST_PROBE_BYTES = 32L

/**
 * The FFmpeg pre-open options that this policy sets. The default policy sets none.
 *
 * @throws PlaybackException with [PlaybackError.ConfigurationInvalid] for a value FFmpeg cannot take.
 */
internal fun DemuxPolicy.toFFmpegOptions(): Map<String, String> = buildMap {
    when (val depth = probe) {
        ProbeDepth.Default -> Unit
        ProbeDepth.Fast -> {
            put("probesize", "524288")
            put("analyzeduration", "200000")
        }
        ProbeDepth.Thorough -> {
            put("probesize", "67108864")
            put("analyzeduration", "20000000")
        }
        is ProbeDepth.Custom -> {
            if (depth.bytes < SMALLEST_PROBE_BYTES) {
                throw PlaybackException(
                    PlaybackError.ConfigurationInvalid(
                        "ProbeDepth.Custom asks for ${depth.bytes} bytes, and FFmpeg reads at least $SMALLEST_PROBE_BYTES.",
                    ),
                )
            }
            put("probesize", depth.bytes.toString())
            // FFmpeg reads 0 as its own default of five seconds, so a shorter duration rounds up.
            put("analyzeduration", depth.duration.inWholeMicroseconds.coerceAtLeast(1).toString())
        }
    }
    val flags = buildList {
        if (corruptPackets == CorruptPackets.Drop) add("discardcorrupt")
        if (generateTimestamps) add("genpts")
        if (lowLatency) add("nobuffer")
    }
    // The leading plus adds to FFmpeg's default flags. Without it they are replaced.
    if (flags.isNotEmpty()) put("fflags", flags.joinToString(separator = "+", prefix = "+"))
    if (lowLatency) put("max_delay", "0")
    if (skipInitialBytes > 0) put("skip_initial_bytes", skipInitialBytes.toString())
}
