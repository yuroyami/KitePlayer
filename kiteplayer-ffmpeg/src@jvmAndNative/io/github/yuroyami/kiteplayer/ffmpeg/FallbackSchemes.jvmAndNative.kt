package io.github.yuroyami.kiteplayer.ffmpeg

internal actual val fallbackSchemes: Set<String> =
    setOf("file", "fd", "pipe", "data", "http", "tcp", "udp", "rtp", "rtsp", "rtmp")
