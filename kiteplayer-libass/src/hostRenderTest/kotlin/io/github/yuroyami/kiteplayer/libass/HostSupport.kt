package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.OutputBackend

/** The platform's real output backend, for the end-to-end test: Apple's on the Mac, the desktop's on the JVM. */
internal expect fun hostOutputBackend(): OutputBackend

/** Where the Gradle test task put the fixtures, or the relative default for a hand-run binary. */
internal expect fun mediaDir(): String

/** The environment's answer for one variable, or null. */
internal expect fun environment(name: String): String?
