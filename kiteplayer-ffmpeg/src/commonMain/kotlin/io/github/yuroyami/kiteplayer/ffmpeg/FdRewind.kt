package io.github.yuroyami.kiteplayer.ffmpeg

/**
 * Rewinds the descriptor an `fd` pre-open option names to byte zero, so every open of the same
 * [io.github.yuroyami.kiteplayer.MediaItem] starts at the start (2026-08-17, the track-change
 * crash). FFmpeg's fd protocol dups the descriptor and never rewinds it, and a dup SHARES the
 * file offset, so a second open of the same item (a track change's container rebuild, a loop's
 * reopen) probed from wherever the first open's demuxing left the shared offset and failed
 * with AVERROR_INVALIDDATA.
 *
 * Best effort by design: an unseekable descriptor (a pipe) refuses the seek and is exactly the
 * streamed case FFmpeg handles on its own, and a platform with no way to seek a raw integer
 * descriptor answers with a no-op. Failure here must never fail an open that might succeed.
 */
internal expect fun rewindFdOption(options: Map<String, String>)
