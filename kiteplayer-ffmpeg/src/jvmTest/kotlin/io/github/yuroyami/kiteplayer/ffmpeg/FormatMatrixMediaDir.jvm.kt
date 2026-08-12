package io.github.yuroyami.kiteplayer.ffmpeg

/** The Gradle test task sets the system property for every JVM test. */
internal actual fun formatMatrixMediaDir(): String? =
    System.getProperty("KITEPLAYER_TESTMEDIA") ?: System.getenv("KITEPLAYER_TESTMEDIA") ?: "testmedia"
