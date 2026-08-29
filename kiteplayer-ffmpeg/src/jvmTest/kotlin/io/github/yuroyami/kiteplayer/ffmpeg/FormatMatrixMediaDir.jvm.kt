package io.github.yuroyami.kiteplayer.ffmpeg

/**
 * The repository's testmedia tree, or wherever the test task points.
 *
 * The JVM ran no real media until phase W, because KiteFFmpeg's jvm variant was a placeholder. It
 * carries the JNI adapter now, so the desktop JVM runs the same 17.5 matrix the native targets do.
 */
internal actual fun formatMatrixMediaDir(): String? =
    System.getenv("KITEPLAYER_TESTMEDIA") ?: "testmedia"
