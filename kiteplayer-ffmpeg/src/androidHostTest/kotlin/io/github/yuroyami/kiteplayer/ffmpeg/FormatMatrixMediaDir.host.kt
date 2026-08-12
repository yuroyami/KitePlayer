package io.github.yuroyami.kiteplayer.ffmpeg

/** The Android host JVM loads no device JNI library, so the matrix cannot run here. */
internal actual fun formatMatrixMediaDir(): String? = null
