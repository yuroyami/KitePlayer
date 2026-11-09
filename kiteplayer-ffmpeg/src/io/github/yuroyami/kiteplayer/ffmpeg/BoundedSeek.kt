package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException

/** Prefer at most two seconds of preroll. A sparse index may require one unrestricted attempt. */
internal inline fun seekBackward(targetMicros: Long, seek: (Long, Long?) -> Unit) {
    val floor = if (targetMicros > 2_000_000L) targetMicros - 2_000_000L else minOf(0L, targetMicros)
    try {
        seek(targetMicros, floor)
    } catch (failure: FFmpegException) {
        when (failure.error) {
            // Unsupported is FFmpeg's ENOSYS, which KiteFFmpeg typed as AvError until its #245:
            // a demuxer that cannot take the floor may still take the plain seek (#967).
            is FFmpegError.InvalidArgument, is FFmpegError.AvError, is FFmpegError.EndOfFile,
            is FFmpegError.Unsupported -> Unit
            else -> throw failure
        }
        // Cancellation, interrupted I/O, allocation failures and caller errors never reach here.
        seek(targetMicros, null)
    }
}
