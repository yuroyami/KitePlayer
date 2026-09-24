@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFNumberRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFNumberSInt64Type
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreVideo.CVMetalTextureCacheCreate
import platform.CoreVideo.CVMetalTextureCacheCreateTextureFromImage
import platform.CoreVideo.CVMetalTextureCacheFlush
import platform.CoreVideo.CVMetalTextureCacheRef
import platform.CoreVideo.CVMetalTextureCacheRefVar
import platform.CoreVideo.CVMetalTextureGetTexture
import platform.CoreVideo.CVMetalTextureRefVar
import platform.CoreVideo.CVPixelBufferPoolCreate
import platform.CoreVideo.CVPixelBufferPoolCreatePixelBuffer
import platform.CoreVideo.CVPixelBufferPoolRef
import platform.CoreVideo.CVPixelBufferPoolRefVar
import platform.CoreVideo.CVPixelBufferPoolRelease
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVMetalTextureUsage
import platform.CoreVideo.kCVPixelBufferHeightKey
import platform.CoreVideo.kCVPixelBufferIOSurfacePropertiesKey
import platform.CoreVideo.kCVPixelBufferMetalCompatibilityKey
import platform.CoreVideo.kCVPixelBufferPixelFormatTypeKey
import platform.CoreVideo.kCVPixelBufferWidthKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLTextureProtocol
import platform.Metal.MTLTextureUsageRenderTarget
import platform.Metal.MTLTextureUsageShaderRead

/**
 * Draws a picture and its subtitles into one new pixel buffer, for a layer that shows nothing but
 * pixel buffers.
 *
 * The buffer is BGRA and IOSurface backed, so a display layer can show it. It comes from a pool
 * sized to the picture, so text that stays up for a whole scene does not allocate a buffer per
 * frame. The picture fills the buffer the way the layer shows it without text: in its stored
 * orientation, and stretched only to its pixel aspect. The text lands where the engine laid it out.
 *
 * Not thread safe. The renderer calls it under its own lock.
 */
internal class SubtitleBurner private constructor(device: MTLDeviceProtocol) {

    private val composer = MetalFrameComposer(device)
    private val textureCache: CVMetalTextureCacheRef = createTargetCache(device)
    private var pool: CVPixelBufferPoolRef? = null
    private var poolWidth = 0
    private var poolHeight = 0

    /**
     * A new buffer showing [pixels] with [overlay] above it. The caller owns one reference to it.
     *
     * Null when any step refuses, and the caller then shows the picture without text.
     */
    fun burn(pixels: CVPixelBufferRef, facts: PictureFacts, overlay: SubtitleOverlay): CVPixelBufferRef? {
        val picture = MetalPicture.CorePixelBuffer(pixels)
        if (!composer.canEncode(picture)) return null
        val width = facts.size.displayWidth.coerceAtLeast(1)
        val height = facts.size.height.coerceAtLeast(1)
        val target = pooledBuffer(width, height) ?: return null
        val drawn = runCatching { draw(target, facts, picture, overlay, width, height) }.getOrDefault(false)
        if (!drawn) {
            CVPixelBufferRelease(target)
            return null
        }
        return target
    }

    /** Gives back the GPU objects, the texture cache and the pool. */
    fun close() {
        composer.close()
        CVMetalTextureCacheFlush(textureCache, 0uL)
        CFRelease(textureCache)
        pool?.let { CVPixelBufferPoolRelease(it) }
        pool = null
    }

    private fun draw(
        target: CVPixelBufferRef,
        facts: PictureFacts,
        picture: MetalPicture,
        overlay: SubtitleOverlay,
        width: Int,
        height: Int,
    ): Boolean = memScoped {
        val wrapped = alloc<CVMetalTextureRefVar>()
        val status = CVMetalTextureCacheCreateTextureFromImage(
            allocator = null,
            textureCache = textureCache,
            sourceImage = target,
            textureAttributes = null,
            pixelFormat = MTLPixelFormatBGRA8Unorm,
            width = width.toULong(),
            height = height.toULong(),
            planeIndex = 0uL,
            textureOut = wrapped.ptr,
        )
        val textureRef = wrapped.value ?: return@memScoped false
        if (status != kCVReturnSuccess) {
            CFRelease(textureRef)
            return@memScoped false
        }
        try {
            val texture = CVMetalTextureGetTexture(textureRef) as? MTLTextureProtocol ?: return@memScoped false
            // Waited for, because the layer reads the buffer as soon as it has the sample.
            composer.encode(
                target = texture,
                frame = facts,
                picture = picture,
                overlay = overlay,
                viewportWidth = width,
                viewportHeight = height,
                quadOverride = FILL_QUAD,
                toneMapped = true,
            ).waitUntilCompleted()
            true
        } finally {
            CFRelease(textureRef)
        }
    }

    private fun pooledBuffer(width: Int, height: Int): CVPixelBufferRef? {
        if (pool == null || poolWidth != width || poolHeight != height) {
            pool?.let { CVPixelBufferPoolRelease(it) }
            pool = createPool(width, height)
            poolWidth = width
            poolHeight = height
        }
        val from = pool ?: return null
        return memScoped {
            val out = alloc<CVPixelBufferRefVar>()
            if (CVPixelBufferPoolCreatePixelBuffer(null, from, out.ptr) != kCVReturnSuccess) null else out.value
        }
    }

    companion object {
        /** The whole target, unturned: the picture fills the buffer edge to edge. */
        private val FILL_QUAD = floatArrayOf(1f, 1f, 1f, 0f, 0f, 1f, 0f, 0f)

        /** Null on a machine with no Metal device, where subtitles are left out of the picture. */
        fun createOrNull(): SubtitleBurner? {
            val device = MTLCreateSystemDefaultDevice() ?: return null
            return runCatching { SubtitleBurner(device) }.getOrNull()
        }
    }
}

/**
 * The facts about a frame that the composer reads, kept after the frame is closed.
 *
 * The pixels live in a pixel buffer the renderer holds. These say how to read them.
 */
internal class PictureFacts(
    override val size: VideoSize,
    override val colorSpace: ColorSpaceInfo,
) : VideoFrame {
    override val pts: Pts = Pts.Zero
    override val duration: Pts? = null
    override val pixelFormat: PlayerPixelFormat = PlayerPixelFormat.Opaque
    override val hardwareSurface: HwSurfaceKind? = null
    override val generation: Generation = Generation.Initial
    override fun close() = Unit

    companion object {
        fun of(frame: VideoFrame): PictureFacts = PictureFacts(frame.size, frame.colorSpace)
    }
}

/** A texture cache whose textures can be drawn into, which is what the composer does to them. */
private fun createTargetCache(device: MTLDeviceProtocol): CVMetalTextureCacheRef = memScoped {
    val usage = cfNumber((MTLTextureUsageRenderTarget or MTLTextureUsageShaderRead).toLong())
    val attributes = CFDictionaryCreateMutable(
        null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    )
    CFDictionarySetValue(attributes, kCVMetalTextureUsage, usage)
    val out = alloc<CVMetalTextureCacheRefVar>()
    val status = CVMetalTextureCacheCreate(
        allocator = null,
        cacheAttributes = null,
        metalDevice = device as objcnames.protocols.MTLDeviceProtocol,
        textureAttributes = attributes,
        cacheOut = out.ptr,
    )
    CFRelease(attributes)
    usage?.let { CFRelease(it) }
    val cache = out.value
    check(status == kCVReturnSuccess && cache != null) { "CVMetalTextureCacheCreate failed: $status" }
    cache
}

/** BGRA, IOSurface backed and Metal compatible: what both the composer and the layer need. */
private fun createPool(width: Int, height: Int): CVPixelBufferPoolRef? = memScoped {
    val format = cfNumber(kCVPixelFormatType_32BGRA.toLong())
    val widthValue = cfNumber(width.toLong())
    val heightValue = cfNumber(height.toLong())
    val surfaceProperties = CFDictionaryCreateMutable(
        null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    )
    val attributes = CFDictionaryCreateMutable(
        null, 5, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
    )
    CFDictionarySetValue(attributes, kCVPixelBufferPixelFormatTypeKey, format)
    CFDictionarySetValue(attributes, kCVPixelBufferWidthKey, widthValue)
    CFDictionarySetValue(attributes, kCVPixelBufferHeightKey, heightValue)
    CFDictionarySetValue(attributes, kCVPixelBufferIOSurfacePropertiesKey, surfaceProperties)
    CFDictionarySetValue(attributes, kCVPixelBufferMetalCompatibilityKey, kCFBooleanTrue)
    val out = alloc<CVPixelBufferPoolRefVar>()
    val status = CVPixelBufferPoolCreate(null, null, attributes, out.ptr)
    CFRelease(attributes)
    CFRelease(surfaceProperties)
    listOf(format, widthValue, heightValue).forEach { value -> value?.let { CFRelease(it) } }
    if (status == kCVReturnSuccess) out.value else null
}

private fun cfNumber(value: Long): CFNumberRef? = memScoped {
    val holder = alloc<LongVar>()
    holder.value = value
    CFNumberCreate(null, kCFNumberSInt64Type, holder.ptr)
}
