package io.github.yuroyami.kiteplayer.ffmpeg

/** The Android host JVM loads no device JNI library, so the matrix cannot run here. */
internal actual fun formatMatrixMediaDir(): String? = null

/** No filesystem worth writing a report to, and no matrix run here either. */
internal actual fun writeConformanceReport(fileName: String, markdown: String): String? = null

internal actual fun conformancePlatformName(): String = "android-host"
