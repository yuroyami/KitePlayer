@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLPixelFormatRGBA8Unorm
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * 17.11 SOL-P7: pipeline states are immutable and device-owned, so compiling them once per
 * renderer was work every renderer paid for the same objects.
 */
class MetalPipelinesTest {

    private fun device(): MTLDeviceProtocol =
        assertNotNull(MTLCreateSystemDefaultDevice(), "this machine has no Metal device")

    @Test
    fun `one device and one target format share one compiled set`() {
        val device = device()
        val first = MetalPipelines.of(device, MTLPixelFormatBGRA8Unorm)
        val second = MetalPipelines.of(device, MTLPixelFormatBGRA8Unorm)
        assertSame(first, second, "a second composer on this device must reuse the compiled set")
        assertSame(first.picture, second.picture)
        assertSame(first.overlay, second.overlay)
    }

    @Test
    fun `a different target format gets its own set`() {
        val device = device()
        val bgra = MetalPipelines.of(device, MTLPixelFormatBGRA8Unorm)
        val rgba = MetalPipelines.of(device, MTLPixelFormatRGBA8Unorm)
        assertNotSame(bgra, rgba, "the attachment format is baked into a pipeline state")
    }
}
