package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaByteSource

// Windows has no pread, so FFmpeg's fd protocol keeps the descriptor.
internal actual fun descriptorByteSource(descriptor: Int): MediaByteSource? = null
