package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaByteSource

// A browser has no file descriptors.
internal actual fun descriptorByteSource(descriptor: Int): MediaByteSource? = null
