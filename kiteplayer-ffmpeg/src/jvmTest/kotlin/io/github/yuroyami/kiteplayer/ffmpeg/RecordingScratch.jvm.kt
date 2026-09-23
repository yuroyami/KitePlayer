package io.github.yuroyami.kiteplayer.ffmpeg

internal actual fun recordingScratchPath(name: String): String =
    java.io.File(System.getProperty("java.io.tmpdir"), name).path
