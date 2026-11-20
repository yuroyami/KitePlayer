package io.github.yuroyami.kiteplayer.ffmpeg

/** js has no media directory and no FFmpeg, so the format matrix does not run there. */
internal actual fun formatMatrixMediaDir(): String? = null

/** No filesystem to write a report to. */
internal actual fun writeConformanceReport(fileName: String, markdown: String): String? = null

internal actual fun conformancePlatformName(): String = "js"
