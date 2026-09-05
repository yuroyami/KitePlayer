@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.output.AppleOutputBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal actual fun hostOutputBackend(): OutputBackend = AppleOutputBackend

internal actual fun mediaDir(): String = environment("KITEPLAYER_TESTMEDIA") ?: "testmedia"

internal actual fun environment(name: String): String? = getenv(name)?.toKString()
