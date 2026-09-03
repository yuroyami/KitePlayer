package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteplayer.MediaItem

/**
 * Opens a KiteFFmpeg source for an item the way the playback backend would: through the item's
 * own byte reader when it has one, otherwise through FFmpeg's protocols on the URI, with the
 * item's raw open options either way. The caller closes it.
 */
internal suspend fun openSource(item: MediaItem): MediaSource {
    val factory = item.io
    return if (factory != null) {
        MediaSource.open(BlockingMediaIo(factory()), item.openOptions)
    } else {
        MediaSource.open(item.uri, item.openOptions)
    }
}
