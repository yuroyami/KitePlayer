@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease

/**
 * Overlay bitmaps as CGImages, rebuilt only when the overlay's content hash moves (17.11 SOL-P7).
 *
 * The CPU fallbacks used to build one CGImage per overlay image on EVERY draw, so a cue held for a
 * second was built sixty times over. The Metal composer already keys its overlay textures the same
 * way, so this is that law for Core Graphics.
 *
 * Not thread-safe by design: each renderer's own worker owns its cache and is the only caller.
 */
internal class OverlayImageCache(
    /** Turns one bitmap into a CGImage the cache then owns. Null means the bitmap was refused. */
    private val build: (RgbaBitmap) -> CGImageRef?,
) {
    private var key: Long = Long.MIN_VALUE
    private var images: List<CGImageRef?> = emptyList()

    /** The CGImages for [overlay], in its own image order. Every reference stays cache-owned. */
    fun imagesFor(overlay: SubtitleOverlay): List<CGImageRef?> {
        if (key == overlay.contentHash && images.size == overlay.images.size) return images
        release()
        images = overlay.images.map { build(it.bitmap) }
        key = overlay.contentHash
        return images
    }

    /** Gives every held reference back. The renderer's close calls this once the worker is out. */
    fun release() {
        images.forEach { it?.let(::CGImageRelease) }
        images = emptyList()
        key = Long.MIN_VALUE
    }
}
