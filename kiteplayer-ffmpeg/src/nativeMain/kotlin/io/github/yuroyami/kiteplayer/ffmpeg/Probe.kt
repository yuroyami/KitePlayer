package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kitecodec.MediaSource

/**
 * Opens [path], counts its streams and closes it.
 *
 * Wrapped the same way [KiteCodecMediaBackend.open] is: this is the other place KitePlayer reaches
 * KiteCodec's FFmpeg identity gate, so a rejection here has to arrive as the same typed
 * `PlaybackError.ConfigurationInvalid` rather than as a bare `FFmpegException`. A probe that reported an
 * ABI rejection as "this file is unreadable" would send the reader looking at the file.
 */
internal suspend fun probeOpen(path: String): Int = mappingFFmpegRuntimeRejection {
    MediaSource.open(path).use { src ->
        src.streams.size
    }
}
