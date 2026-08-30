package io.github.yuroyami.kiteplayer.ffmpeg

/**
 * There is no media directory on the web, because there is no filesystem to hold one.
 *
 * The format matrix reads clips off disk, so every matrix row skips itself here rather than
 * failing. That is the same answer the Android host tests give, and for the same reason: the
 * absence is the environment rather than a gap in coverage.
 */
internal actual fun formatMatrixMediaDir(): String? = null
