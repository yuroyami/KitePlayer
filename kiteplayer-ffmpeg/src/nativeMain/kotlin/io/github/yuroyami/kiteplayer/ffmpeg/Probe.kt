package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kitecodec.MediaSource

internal suspend fun probeOpen(path: String): Int {
    MediaSource.open(path).use { src ->
        return src.streams.size
    }
}
