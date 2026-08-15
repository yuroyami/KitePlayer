package io.github.yuroyami.kiteplayer.ffmpeg

/** JVM has no real-media matrix because its KiteCodec dependency is a placeholder backend. */
internal actual fun formatMatrixMediaDir(): String? = null
