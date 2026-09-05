package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.output.DesktopOutputBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend

internal actual fun hostOutputBackend(): OutputBackend = DesktopOutputBackend

internal actual fun mediaDir(): String = environment("KITEPLAYER_TESTMEDIA") ?: "testmedia"

internal actual fun environment(name: String): String? = System.getenv(name)
