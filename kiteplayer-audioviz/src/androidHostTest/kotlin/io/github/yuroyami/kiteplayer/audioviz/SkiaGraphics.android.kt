package io.github.yuroyami.kiteplayer.audioviz

import org.junit.Assume

/**
 * Skips the calling test. The Android host tests run on a JVM whose android.graphics classes are
 * stubs, so a Compose `Path` or `ImageBitmap` cannot be made there. The skiko targets run it.
 */
internal actual fun useSkiaGraphics() {
    Assume.assumeTrue("Compose graphics need a device or a skiko target", false)
}
