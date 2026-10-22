package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaByteSource

// The JVM has no public way to read a raw descriptor number, so FFmpeg's fd protocol keeps it.
internal actual fun descriptorByteSource(descriptor: Int): MediaByteSource? = null
