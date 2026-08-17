@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGImageRef
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 17.11 SOL-P7: a cue held on screen for a second used to have its CGImages rebuilt on every
 * single frame. The builder is counted rather than the pixels, because the pixels never changed:
 * that is the whole complaint.
 */
class OverlayImageCacheTest {

    private fun overlay(hash: Long, images: Int = 1) = SubtitleOverlay(
        images = List(images) {
            OverlayImage(x = 0, y = 0, bitmap = RgbaBitmap(2, 1, ByteArray(2 * 4)))
        },
        viewportWidth = 16,
        viewportHeight = 9,
        contentHash = hash,
    )

    @Test
    fun `an unchanged cue is built once however many times it is drawn`() {
        var builds = 0
        // Null stands in for a built image: the cache must not care, and never releases a null.
        val cache = OverlayImageCache { builds++; null as CGImageRef? }
        val cue = overlay(hash = 7L)
        repeat(60) { cache.imagesFor(cue) }
        assertEquals(1, builds, "sixty draws of one cue are one build")
    }

    @Test
    fun `a new cue is built again`() {
        var builds = 0
        val cache = OverlayImageCache { builds++; null as CGImageRef? }
        cache.imagesFor(overlay(hash = 7L))
        cache.imagesFor(overlay(hash = 8L))
        assertEquals(2, builds, "a moved content hash is a different cue")
    }

    @Test
    fun `a release forces the next draw to build`() {
        var builds = 0
        val cache = OverlayImageCache { builds++; null as CGImageRef? }
        val cue = overlay(hash = 7L)
        cache.imagesFor(cue)
        cache.release()
        cache.imagesFor(cue)
        assertEquals(2, builds, "a released cache holds nothing")
    }
}
