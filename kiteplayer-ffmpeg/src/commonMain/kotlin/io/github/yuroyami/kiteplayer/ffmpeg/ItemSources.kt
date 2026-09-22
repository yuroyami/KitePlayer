package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteplayer.MediaItem

/**
 * Opens a KiteFFmpeg source for [item]. Playback, thumbnails, waveforms and loudness all open
 * through here, so each gets the same options: [preOpenOptions] builds them from the typed fields
 * and the raw ones, and refuses a collision before any reader exists. The bytes come from the
 * item's own reader when it has one, otherwise from FFmpeg's protocols on the URI. The caller
 * closes the source.
 */
internal suspend fun openSource(item: MediaItem): MediaSource {
    val options = preOpenOptions(item)
    rewindFdOption(options)
    // Called once per open: the reader it makes belongs to this source and is closed with it.
    val io = item.io?.open()
    return when {
        // The custom AVIO bridge: the reader carries the media, with no path and no FFmpeg protocol.
        io != null -> MediaSource.open(BlockingMediaIo(io), options)
        options.isEmpty() -> MediaSource.open(item.uri)
        // Keys the demuxer did not consume come back from KiteFFmpeg instead of being dropped.
        else -> MediaSource.open(item.uri, options)
    }
}
