@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.set
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVFoundation.enqueueSampleBuffer
import platform.AVFoundation.flushAndRemoveImage
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreMedia.CMSampleBufferCreateReadyWithImageBuffer
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionCreateForImageBuffer
import platform.CoreMedia.CMVideoFormatDescriptionRefVar
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferRetain
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferIOSurfacePropertiesKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.CoreVideo.kCVReturnSuccess
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

/**
 * Presents frames into an `AVSampleBufferDisplayLayer`.
 *
 * This layer exists for one reason: it is the only content a picture-in-picture controller accepts
 * from a player that is not the system player. The Metal renderer stays the general path, and this
 * one is chosen when the application wants the small window.
 *
 * Frames arrive as pixel buffers. A hardware frame already is one and is retained rather than
 * copied. A software frame is copied into one, which costs a pass over the picture, so this
 * renderer earns its price only while the small window matters.
 *
 * The engine paces, not the layer. No control timebase is set, so the layer shows each sample as
 * it is enqueued and the video scheduler stays the single authority on when a frame is due. Giving
 * the layer a timebase as well would be two schedules arguing over the same picture.
 *
 * Formats: NV12, planar 4:2:0 whose chroma is interleaved on the way in, and BGRA. Anything else
 * is refused, which the engine counts as a dropped frame rather than a failure.
 */
public class SampleBufferVideoRenderer internal constructor(
    private val layer: AVSampleBufferDisplayLayer,
    private val resolve: MetalPictureResolver,
    private val enqueueOnMain: (block: () -> Unit) -> Unit,
) : VideoRenderer {

    public constructor(
        layer: AVSampleBufferDisplayLayer,
        resolve: MetalPictureResolver,
    ) : this(
        layer = layer,
        resolve = resolve,
        enqueueOnMain = { block -> dispatch_async(dispatch_get_main_queue()) { block() } },
    )

    private val presented = atomic(0L)
    private val failed = atomic(0L)
    private val closed = atomic(false)
    private val eventFlow = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 8)

    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    /** Frames this renderer handed to the layer. */
    public val presentedFrames: Long get() = presented.value

    /** Frames it could not turn into a pixel buffer at all. */
    public val failedFrames: Long get() = failed.value

    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> =
        setOf(HwSurfaceKind.CoreVideoPixelBuffer)

    override fun supports(format: PlayerPixelFormat): Boolean = format in SUPPORTED

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        val pixels = frame.use { open ->
            if (closed.value) null else pixelBufferFor(open)
        }
        if (pixels == null) {
            failed.incrementAndGet()
            return false
        }
        val sample = sampleBufferFor(pixels, targetNanos)
        CVPixelBufferRelease(pixels)
        if (sample == null) {
            failed.incrementAndGet()
            return false
        }
        enqueueOnMain {
            layer.enqueueSampleBuffer(sample)
            CFRelease(sample)
        }
        presented.incrementAndGet()
        return true
    }

    override fun vsyncIntervalNanos(): Long? = null

    /** The layer's bounds and gravity belong to whoever owns it, as with the Core Graphics path. */
    override fun setViewport(width: Int, height: Int, scale: Float): Unit = Unit

    /**
     * Not composited here.
     *
     * The layer shows sample buffers and nothing else, so text would have to be blended into every
     * picture at a CPU pass per frame. The view draws its own subtitle layer above this one
     * instead, which costs nothing and stays correct while the video is paused.
     */
    override suspend fun setOverlay(overlay: SubtitleOverlay?): Unit = Unit

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        enqueueOnMain { layer.flushAndRemoveImage() }
    }

    /** A hardware frame is retained. A software frame is copied. Null refuses the frame. */
    private fun pixelBufferFor(frame: VideoFrame): CVPixelBufferRef? =
        when (val picture = resolve.resolve(frame)) {
            is MetalPicture.CorePixelBuffer -> CVPixelBufferRetain(picture.buffer.reinterpret())
            is MetalPicture.SoftwarePlanes -> copyIntoPixelBuffer(picture, frame.colorSpace.fullRange)
            null -> null
        }

    private fun sampleBufferFor(pixels: CVPixelBufferRef, targetNanos: Long): platform.CoreMedia.CMSampleBufferRef? =
        memScoped {
            val description = alloc<CMVideoFormatDescriptionRefVar>()
            if (CMVideoFormatDescriptionCreateForImageBuffer(null, pixels, description.ptr) != 0) return null
            val timing = alloc<CMSampleTimingInfo>()
            // Nanoseconds as the timescale, which is exactly what the engine's clock counts in.
            CMTimeMake(targetNanos, NANOS_PER_SECOND).place(timing.presentationTimeStamp.ptr)
            CMTimeMake(0, NANOS_PER_SECOND).place(timing.decodeTimeStamp.ptr)
            CMTimeMake(0, NANOS_PER_SECOND).place(timing.duration.ptr)
            val sample = alloc<CMSampleBufferRefVar>()
            val status = CMSampleBufferCreateReadyWithImageBuffer(
                allocator = null,
                imageBuffer = pixels,
                formatDescription = description.value,
                sampleTiming = timing.ptr,
                sampleBufferOut = sample.ptr,
            )
            description.value?.let { CFRelease(it) }
            if (status != 0) null else sample.value
        }

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000
        val SUPPORTED = setOf(
            PlayerPixelFormat.Nv12,
            PlayerPixelFormat.Yuv420p,
            PlayerPixelFormat.Bgra,
        )
    }
}

/**
 * Copies decoded planes into a fresh pixel buffer, or null when the format is not one of the three.
 *
 * Planar 4:2:0 arrives as three planes and leaves as two: Core Video's 8-bit 4:2:0 is bi-planar,
 * so the two chroma planes are interleaved on the way in. That interleave is the whole reason a
 * software frame costs a pass here.
 */
internal fun copyIntoPixelBuffer(
    picture: MetalPicture.SoftwarePlanes,
    fullRange: Boolean,
): CVPixelBufferRef? {
    val type = when (picture.format) {
        PlayerPixelFormat.Nv12, PlayerPixelFormat.Yuv420p ->
            if (fullRange) kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
            else kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        PlayerPixelFormat.Bgra -> kCVPixelFormatType_32BGRA
        else -> return null
    }
    return memScoped {
        val out = alloc<CVPixelBufferRefVar>()
        // An IOSurface is what makes the buffer presentable by a display layer at all. Built in
        // CoreFoundation rather than Foundation so the key stays the CFString the API declares.
        val surfaceProperties = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        val attributes = CFDictionaryCreateMutable(
            null, 1, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionarySetValue(attributes, kCVPixelBufferIOSurfacePropertiesKey, surfaceProperties)
        val created = CVPixelBufferCreate(
            allocator = null,
            width = picture.width.toULong(),
            height = picture.height.toULong(),
            pixelFormatType = type,
            pixelBufferAttributes = attributes,
            pixelBufferOut = out.ptr,
        )
        CFRelease(attributes)
        CFRelease(surfaceProperties)
        val buffer = out.value
        if (created != kCVReturnSuccess || buffer == null) return@memScoped null
        CVPixelBufferLockBaseAddress(buffer, 0uL)
        val filled = runCatching {
            when (picture.format) {
                PlayerPixelFormat.Bgra -> {
                    copyRows(picture.planes[0], CVPixelBufferGetBaseAddress(buffer), CVPixelBufferGetBytesPerRow(buffer).toInt())
                }
                PlayerPixelFormat.Nv12 -> {
                    copyRows(picture.planes[0], CVPixelBufferGetBaseAddressOfPlane(buffer, 0uL), CVPixelBufferGetBytesPerRowOfPlane(buffer, 0uL).toInt())
                    copyRows(picture.planes[1], CVPixelBufferGetBaseAddressOfPlane(buffer, 1uL), CVPixelBufferGetBytesPerRowOfPlane(buffer, 1uL).toInt())
                }
                else -> {
                    copyRows(picture.planes[0], CVPixelBufferGetBaseAddressOfPlane(buffer, 0uL), CVPixelBufferGetBytesPerRowOfPlane(buffer, 0uL).toInt())
                    interleaveChroma(
                        u = picture.planes[1],
                        v = picture.planes[2],
                        into = CVPixelBufferGetBaseAddressOfPlane(buffer, 1uL),
                        destinationBytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(buffer, 1uL).toInt(),
                    )
                }
            }
        }.isSuccess
        CVPixelBufferUnlockBaseAddress(buffer, 0uL)
        if (filled) buffer else { CVPixelBufferRelease(buffer); null }
    }
}

/** Row by row, because the source and the destination almost never share a stride. */
private fun copyRows(plane: MetalPicture.SoftwarePlanes.Plane, into: COpaquePointer?, destinationBytesPerRow: Int) {
    val destination = into ?: error("the pixel buffer gave no base address")
    val width = minOf(plane.bytesPerRow, destinationBytesPerRow)
    plane.bytes.usePinned { pinned ->
        for (row in 0 until plane.rows) {
            memcpy(
                (destination.reinterpret<ByteVar>() + row.toLong() * destinationBytesPerRow),
                pinned.addressOf(row * plane.bytesPerRow),
                width.toULong(),
            )
        }
    }
}

/** Two chroma planes in, one interleaved plane out, which is the layout Core Video wants. */
private fun interleaveChroma(
    u: MetalPicture.SoftwarePlanes.Plane,
    v: MetalPicture.SoftwarePlanes.Plane,
    into: COpaquePointer?,
    destinationBytesPerRow: Int,
) {
    val destination = (into ?: error("the pixel buffer gave no chroma base address")).reinterpret<ByteVar>()
    val samples = minOf(u.bytesPerRow, v.bytesPerRow, destinationBytesPerRow / 2)
    for (row in 0 until minOf(u.rows, v.rows)) {
        val destinationRow = destination + row.toLong() * destinationBytesPerRow
        val uRow = row * u.bytesPerRow
        val vRow = row * v.bytesPerRow
        for (sample in 0 until samples) {
            destinationRow!![sample * 2] = u.bytes[uRow + sample]
            destinationRow[sample * 2 + 1] = v.bytes[vRow + sample]
        }
    }
}
