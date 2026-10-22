package io.github.yuroyami.kiteplayer.ffmpeg

/** The bytes of the file at [path], or null when there is no such file. */
internal expect fun readTestFile(path: String): ByteArray?
