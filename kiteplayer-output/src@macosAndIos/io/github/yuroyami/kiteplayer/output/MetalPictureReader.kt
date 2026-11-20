@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLPixelFormatRGBA8Unorm
import platform.Metal.MTLRegionMake2D
import platform.Metal.MTLTextureProtocol

/**
 * Renders one picture offscreen through Metal and reads the RGBA bytes back (the hardware path on
 * Apple). This is what replaces the CPU colour conversion for a consumer that needs BYTES
 * rather than a layer, KiteVideo above all: the YUV-to-RGB arithmetic runs in the fragment
 * shader, a VideoToolbox frame is wrapped with no copy at all, and the CPU pays exactly one
 * readback memcpy, which is the no-CPU-RGBA law with the one copy its Android sibling
 * also pays.
 *
 * The picture comes back at its STORED size, unrotated and without the pixel-aspect stretch:
 * the caller's own geometry (KiteVideo's draw phase) applies those, exactly as it did for the
 * CPU converter's output.
 *
 * NOT thread safe: one reader belongs to one worker thread, the same confinement every renderer
 * in this library keeps. That thread constructs it, reads through it and closes it.
 *
 * The reader holds native storage that no garbage collector frees: [close] it when done.
 */
public class MetalPictureReader public constructor() : AutoCloseable {

    private val device = MTLCreateSystemDefaultDevice()
        ?: error("this machine has no Metal device")
    private val composer = MetalFrameComposer(device, targetFormat = MTLPixelFormatRGBA8Unorm)

    private var target: MTLTextureProtocol? = null
    private var targetWidth = 0
    private var targetHeight = 0
    private var closed = false

    /** What the composer still holds natively, for the ownership tests. */
    internal val heldTextureCaches: Int get() = composer.heldTextureCaches
    internal val heldTextureWrappers: Int get() = composer.heldTextureWrappers

    /** The identity quad: full-viewport, unrotated, untouched texcoords. */
    private val identityQuad = floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f, 0f, 0f)

    /**
     * Renders [picture] and returns `width * height * 4` tightly packed RGBA bytes at the
     * frame's stored size.
     *
     * The reader borrows [frame] and [picture] until the call returns and closes neither. The
     * returned array is the caller's and stays valid after [close].
     *
     * @throws IllegalStateException when the reader is closed.
     */
    public fun readRgba(
        frame: VideoFrame,
        picture: MetalPicture,
        /**
         * True runs the HDR-to-SDR law on an HDR frame (KiteVideo's display path wants what
         * the viewer should SEE); false reads the stored picture raw, which is what the colour
         * instrument compares against the CPU converter. SDR frames are bit-exact either way.
         */
        toneMapped: Boolean = false,
    ): ByteArray {
        check(!closed) { "the Metal picture reader is closed" }
        val width = frame.size.width
        val height = frame.size.height
        require(width > 0 && height > 0) { "frame has no dimensions: ${width}x$height" }
        val texture = if (target != null && targetWidth == width && targetHeight == height) {
            target!!
        } else {
            device.makeTargetTexture(width, height, MTLPixelFormatRGBA8Unorm).also {
                target = it
                targetWidth = width
                targetHeight = height
            }
        }
        val commands = composer.encode(
            target = texture,
            frame = frame,
            picture = picture,
            overlay = null,
            viewportWidth = width,
            viewportHeight = height,
            quadOverride = identityQuad,
            toneMapped = toneMapped,
        )
        commands.waitUntilCompleted()
        val bytes = ByteArray(width * height * 4)
        bytes.usePinned { pinned ->
            texture.getBytes(
                pinned.addressOf(0),
                bytesPerRow = (width * 4).toULong(),
                fromRegion = MTLRegionMake2D(0u, 0u, width.toULong(), height.toULong()),
                mipmapLevel = 0u,
            )
        }
        return bytes
    }

    /**
     * Waits for submitted GPU work, then releases the CoreVideo texture cache and the cached
     * frame storage. A second call does nothing.
     */
    override fun close() {
        if (closed) return
        closed = true
        target = null
        composer.close()
    }
}
