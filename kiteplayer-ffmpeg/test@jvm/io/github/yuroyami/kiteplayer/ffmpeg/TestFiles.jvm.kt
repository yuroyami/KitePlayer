package io.github.yuroyami.kiteplayer.ffmpeg

internal actual fun readTestFile(path: String): ByteArray? =
    java.io.File(path).takeIf { it.isFile }?.readBytes()
