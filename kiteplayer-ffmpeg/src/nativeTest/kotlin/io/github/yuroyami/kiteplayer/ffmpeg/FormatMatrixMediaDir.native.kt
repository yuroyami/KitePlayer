@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.cinterop.toKString

/** Set by the Gradle test task (SIMCTL_CHILD_-forwarded on a simulator). */
internal actual fun formatMatrixMediaDir(): String? =
    platform.posix.getenv("KITEPLAYER_TESTMEDIA")?.toKString() ?: "testmedia"
