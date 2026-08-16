@file:OptIn(ExperimentalForeignApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreVideo.CVMetalTextureCacheCreate
import platform.CoreVideo.CVMetalTextureCacheCreateTextureFromImage
import platform.CoreVideo.CVMetalTextureCacheFlush
import platform.CoreVideo.CVMetalTextureCacheRefVar
import platform.CoreVideo.CVMetalTextureGetTexture
import platform.CoreVideo.CVMetalTextureRefVar
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetHeightOfPlane
import platform.CoreVideo.CVPixelBufferGetPixelFormatType
import platform.CoreVideo.CVPixelBufferGetPlaneCount
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferGetWidthOfPlane
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarFullRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.CoreVideo.kCVReturnSuccess
import platform.CoreFoundation.CFRelease
import platform.Metal.MTLCommandBufferProtocol
import platform.Metal.MTLCommandQueueProtocol
import platform.Metal.MTLDeviceProtocol
import platform.Metal.MTLLoadActionClear
import platform.Metal.MTLPixelFormatBGRA8Unorm
import platform.Metal.MTLPixelFormatR16Unorm
import platform.Metal.MTLPixelFormatR8Unorm
import platform.Metal.MTLPixelFormatRG16Unorm
import platform.Metal.MTLPixelFormatRG8Unorm
import platform.Metal.MTLPixelFormatRGBA8Unorm
import platform.Metal.MTLPrimitiveTypeTriangleStrip
import platform.Metal.MTLRenderPassDescriptor
import platform.Metal.MTLStoreActionStore
import platform.Metal.MTLTextureProtocol

/**
 * Everything between a resolved picture and a finished render target (S2.c): pipeline states,
 * plane texture reuse, the CVMetalTextureCache for zero-copy hardware frames, and one encode
 * routine that draws the letterboxed picture and the overlay quads above it.
 *
 * This class touches NO layer and NO thread: the renderer owns those, and the colour instrument
 * drives this class offscreen on the macOS host, renders into a plain texture and reads the
 * pixels back. That split is the same testability law CanvasTarget gave the Android renderer.
 */
internal class MetalFrameComposer(
    internal val device: MTLDeviceProtocol,
    /** The render target's pixel format: BGRA for a CAMetalLayer, RGBA for an offscreen read. */
    private val targetFormat: ULong = MTLPixelFormatBGRA8Unorm,
) {
    private val library = device.compileKitePlayerLibrary()
    private val picturePipeline = device.makePicturePipeline(library, targetFormat)
    private val overlayPipeline = device.makeOverlayPipeline(library, targetFormat)

    internal val queue: MTLCommandQueueProtocol =
        checkNotNull(device.newCommandQueue()) { "Metal refused a command queue" }

    /** Reused plane textures, replaced when a frame's geometry or format changes. */
    private var planeTextures: List<MTLTextureProtocol> = emptyList()
    private var planeKey: String = ""

    /** The zero-copy wrap cache for VideoToolbox frames. Created on first hardware frame. */
    private var textureCache: CPointer<CVMetalTextureCacheRefVar>? = null

    /** Overlay textures keyed by the overlay's contentHash; rebuilt only when the hash moves. */
    private var overlayTextures: List<Pair<FloatArray, MTLTextureProtocol>> = emptyList()
    private var overlayKey: Long = Long.MIN_VALUE
    private var overlayViewport: Pair<Int, Int> = 0 to 0

    /**
     * Renders [picture] letterboxed into [target] and [overlay] above it.
     *
     * @return the command buffer, already committed. The caller decides whether to wait (tests)
     *         or to let a drawable present it (the renderer).
     */
    fun encode(
        target: MTLTextureProtocol,
        frame: VideoFrame,
        picture: MetalPicture,
        overlay: SubtitleOverlay?,
        viewportWidth: Int,
        viewportHeight: Int,
        presentDrawable: platform.QuartzCore.CAMetalDrawableProtocol? = null,
        /**
         * Overrides the letterbox-and-rotation quad. The offscreen frame reader passes the
         * identity quad to get the STORED picture back raw; a renderer leaves this null.
         */
        quadOverride: FloatArray? = null,
    ): MTLCommandBufferProtocol {
        pictureColor = frame.colorSpace
        val inputs = when (picture) {
            is MetalPicture.SoftwarePlanes -> softwareInputs(picture)
            is MetalPicture.CorePixelBuffer -> hardwareInputs(picture, frame)
        }

        val pass = MTLRenderPassDescriptor()
        val attachment = pass.colorAttachments.objectAtIndexedSubscript(0u)
        attachment.texture = target
        attachment.loadAction = MTLLoadActionClear
        attachment.storeAction = MTLStoreActionStore

        val commands = checkNotNull(queue.commandBuffer()) { "Metal refused a command buffer" }
        val encoder = checkNotNull(commands.renderCommandEncoderWithDescriptor(pass)) {
            "Metal refused a render encoder"
        }
        // Until the completed handler owns it, this function owns the wrapped textures' release:
        // a throw anywhere between wrapping and commit (an encoder refusal, a bad overlay bitmap)
        // must not orphan CVMetalTextureRefs the GPU will never read (17.11 SOL-R7).
        var releaseHandedOff = false
        try {
            try {
                encoder.setRenderPipelineState(picturePipeline)
                val quad = quadOverride ?: quadUniformsFor(frame, viewportWidth, viewportHeight)
                quad.usePinned { pinned ->
                    encoder.setVertexBytes(pinned.addressOf(0), (quad.size * 4).toULong(), atIndex = 0u)
                }
                val colors = inputs.uniforms
                colors.usePinned { pinned ->
                    encoder.setFragmentBytes(pinned.addressOf(0), (colors.size * 4).toULong(), atIndex = 0u)
                }
                inputs.textures.forEachIndexed { index, texture ->
                    encoder.setFragmentTexture(texture, atIndex = index.toULong())
                }
                // Unused plane slots still need a bound texture on some GPUs; bind the first again.
                for (index in inputs.textures.size until 3) {
                    encoder.setFragmentTexture(inputs.textures.first(), atIndex = index.toULong())
                }
                encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip, vertexStart = 0u, vertexCount = 4u)

                if (overlay != null && overlay.images.isNotEmpty()) {
                    refreshOverlayTextures(overlay, viewportWidth, viewportHeight)
                    encoder.setRenderPipelineState(overlayPipeline)
                    overlayTextures.forEach { (quadUniforms, texture) ->
                        quadUniforms.usePinned { pinned ->
                            encoder.setVertexBytes(pinned.addressOf(0), (quadUniforms.size * 4).toULong(), atIndex = 0u)
                        }
                        encoder.setFragmentTexture(texture, atIndex = 0u)
                        encoder.drawPrimitives(MTLPrimitiveTypeTriangleStrip, vertexStart = 0u, vertexCount = 4u)
                    }
                }
            } finally {
                encoder.endEncoding()
            }
            if (presentDrawable != null) commands.presentDrawable(presentDrawable)
            // The wrapped CVMetalTextures must outlive the GPU's read of them: releasing on commit
            // was a use-after-free the first offscreen run crashed on. The completed handler is the
            // moment the GPU is provably done.
            commands.addCompletedHandler { inputs.release() }
            releaseHandedOff = true
            commands.commit()
        } finally {
            if (!releaseHandedOff) inputs.release()
        }
        lastCommands = commands
        return commands
    }

    /** The most recently committed buffer; the serial queue makes waiting on it wait on all. */
    private var lastCommands: MTLCommandBufferProtocol? = null

    /**
     * Releases what the composer owns natively: the CVMetalTextureCache and its nativeHeap
     * holder (17.11 SOL-R6). The GPU is fenced first by waiting on the last committed buffer,
     * which on this serial queue proves every earlier buffer, and therefore every wrapped
     * texture's completed handler, has run. Idempotent; encode after close is a caller bug the
     * texture-cache check will surface.
     */
    fun close() {
        lastCommands?.waitUntilCompleted()
        lastCommands = null
        textureCache?.let { holder ->
            CVMetalTextureCacheFlush(holder.pointed.value, 0uL)
            holder.pointed.value?.let { CFRelease(it) }
            kotlinx.cinterop.nativeHeap.free(holder.rawValue)
        }
        textureCache = null
    }

    private class PictureInputs(
        val textures: List<MTLTextureProtocol>,
        val uniforms: FloatArray,
        val release: () -> Unit = {},
    )

    private fun softwareInputs(picture: MetalPicture.SoftwarePlanes): PictureInputs {
        val recipe = requireNotNull(planeRecipeFor(picture.format)) {
            "the Metal renderer cannot draw ${picture.format}"
        }
        require(picture.planes.size >= recipe.formats.size) {
            "${picture.format} needs ${recipe.formats.size} planes, got ${picture.planes.size}"
        }
        val key = "${picture.format}:${picture.width}x${picture.height}"
        if (planeKey != key) {
            planeTextures = recipe.formats.mapIndexed { index, format ->
                // Chroma planes are ceil-divided, matching how decoders size them: a 1279x719
                // 4:2:0 picture carries 640x360 chroma, and a floor shift would drop the last
                // column and row of every odd-sized frame.
                val width = if (index == 0) picture.width
                    else (picture.width + (1 shl recipe.chromaShiftX) - 1) shr recipe.chromaShiftX
                val height = if (index == 0) picture.height
                    else (picture.height + (1 shl recipe.chromaShiftY) - 1) shr recipe.chromaShiftY
                device.makePlaneTexture(format, width.coerceAtLeast(1), height.coerceAtLeast(1))
            }
            planeKey = key
        }
        planeTextures.forEachIndexed { index, texture -> texture.uploadPlane(picture.planes[index]) }
        val frameColor = MetalColorUniforms.of(pictureColor)
        return PictureInputs(planeTextures, frameColor.packWith(recipe.sampleScale, recipe.mode))
    }

    /** The colour of the picture being encoded; set by [encode]'s caller through the frame. */
    private var pictureColor = io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo.Unspecified

    private fun hardwareInputs(picture: MetalPicture.CorePixelBuffer, frame: VideoFrame): PictureInputs {
        val cache = ensureTextureCache()
        val buffer: CVPixelBufferRef? = interpretCPointer(picture.buffer.rawValue)
        val cvFormat = CVPixelBufferGetPixelFormatType(buffer)
        val planes = CVPixelBufferGetPlaneCount(buffer).toInt()

        val (formats, sampleScale, mode) = when (cvFormat) {
            kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange,
            kCVPixelFormatType_420YpCbCr8BiPlanarFullRange,
            -> Triple(listOf(MTLPixelFormatR8Unorm, MTLPixelFormatRG8Unorm), 1f, 1)
            kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange,
            kCVPixelFormatType_420YpCbCr10BiPlanarFullRange,
            -> Triple(listOf(MTLPixelFormatR16Unorm, MTLPixelFormatRG16Unorm), 65535f / 65472f, 1)
            kCVPixelFormatType_32BGRA -> Triple(listOf(MTLPixelFormatBGRA8Unorm), 1f, 2)
            else -> error("the Metal renderer cannot wrap CVPixelBuffer format $cvFormat")
        }

        val wrapped = ArrayList<Pair<platform.CoreVideo.CVMetalTextureRef?, MTLTextureProtocol>>(formats.size)
        try {
            memScoped {
                formats.forEachIndexed { index, format ->
                    val planeIndex = if (planes == 0) 0 else index
                    // A non-planar buffer (packed BGRA) answers zero to the per-plane size
                    // functions, which sized its texture at nothing (17.11 SOL-R4). The
                    // buffer-level functions are the documented non-planar reading.
                    val width = if (planes == 0) CVPixelBufferGetWidth(buffer)
                        else CVPixelBufferGetWidthOfPlane(buffer, planeIndex.toULong())
                    val height = if (planes == 0) CVPixelBufferGetHeight(buffer)
                        else CVPixelBufferGetHeightOfPlane(buffer, planeIndex.toULong())
                    val out = alloc<CVMetalTextureRefVar>()
                    val rc = CVMetalTextureCacheCreateTextureFromImage(
                        allocator = null,
                        textureCache = cache.pointed.value,
                        sourceImage = buffer,
                        textureAttributes = null,
                        pixelFormat = format,
                        width = width,
                        height = height,
                        planeIndex = planeIndex.toULong(),
                        textureOut = out.ptr,
                    )
                    check(rc == kCVReturnSuccess) { "CVMetalTextureCacheCreateTextureFromImage failed: $rc" }
                    val texture = checkNotNull(CVMetalTextureGetTexture(out.value)) {
                        "a wrapped CVMetalTexture carried no MTLTexture"
                    } as MTLTextureProtocol
                    wrapped += out.value to texture
                }
            }
        } catch (failure: Throwable) {
            // A failure halfway through wrapping owns whatever it already wrapped (SOL-R7).
            wrapped.forEach { (cv, _) -> if (cv != null) CFRelease(cv) }
            throw failure
        }
        val frameColor = MetalColorUniforms.of(pictureColor)
        return PictureInputs(
            textures = wrapped.map { it.second },
            uniforms = frameColor.packWith(sampleScale, mode),
            // No cache flush here: flushing after every frame defeated the cache's whole point
            // (17.11 SOL-R5). The cache is flushed once, at close, or by CoreVideo itself on
            // real invalidation.
            release = {
                wrapped.forEach { (cv, _) -> if (cv != null) CFRelease(cv) }
            },
        )
    }

    private fun ensureTextureCache(): CPointer<CVMetalTextureCacheRefVar> {
        textureCache?.let { return it }
        val holder = kotlinx.cinterop.nativeHeap.alloc<CVMetalTextureCacheRefVar>()
        val rc = CVMetalTextureCacheCreate(
            allocator = null,
            cacheAttributes = null,
            metalDevice = device as objcnames.protocols.MTLDeviceProtocol,
            textureAttributes = null,
            cacheOut = holder.ptr,
        )
        check(rc == kCVReturnSuccess) { "CVMetalTextureCacheCreate failed: $rc" }
        return holder.ptr.also { textureCache = it }
    }

    private fun refreshOverlayTextures(overlay: SubtitleOverlay, viewportWidth: Int, viewportHeight: Int) {
        val viewport = viewportWidth to viewportHeight
        if (overlayKey == overlay.contentHash && overlayViewport == viewport) return
        overlayTextures = overlay.images.map { image ->
            val texture = device.makePlaneTexture(
                MTLPixelFormatRGBA8Unorm,
                image.bitmap.width.coerceAtLeast(1),
                image.bitmap.height.coerceAtLeast(1),
            )
            texture.uploadPlane(
                MetalPicture.SoftwarePlanes.Plane(
                    bytes = image.bitmap.pixels,
                    bytesPerRow = image.bitmap.width * 4,
                    rows = image.bitmap.height,
                ),
            )
            overlayQuadUniforms(image, overlay, viewportWidth, viewportHeight) to texture
        }
        overlayKey = overlay.contentHash
        overlayViewport = viewport
    }
}
