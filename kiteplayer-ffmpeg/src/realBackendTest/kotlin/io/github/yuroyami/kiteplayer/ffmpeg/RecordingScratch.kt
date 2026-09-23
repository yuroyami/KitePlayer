package io.github.yuroyami.kiteplayer.ffmpeg

/** A path for a test's output file [name], in the system's temporary directory. */
internal expect fun recordingScratchPath(name: String): String
