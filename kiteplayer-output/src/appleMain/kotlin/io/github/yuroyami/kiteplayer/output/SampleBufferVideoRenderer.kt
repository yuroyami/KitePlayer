@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package io.github.yuroyami.kiteplayer.output

import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import platform.AVFoundation.AVSampleBufferDisplayLayer
import platform.AVFoundation.AVSampleBufferVideoRenderer
import platform.AVFoundation.enqueueSampleBuffer
import platform.AVFoundation.flushAndRemoveImage
import platform.AVFoundation.sampleBufferRenderer
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFDictionarySetValue
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreMedia.CMSampleBufferCreateReadyWithImageBuffer
import platform.CoreMedia.CMSampleBufferGetSampleAttachmentsArray
import platform.CoreMedia.CMSampleBufferRef
import platform.CoreMedia.CMSampleBufferRefVar
import platform.CoreMedia.CMSampleTimingInfo
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMVideoFormatDescriptionCreateForImageBuffer
import platform.CoreMedia.CMVideoFormatDescriptionRefVar
import platform.CoreMedia.kCMSampleAttachmentKey_DisplayImmediately
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
import platform.CoreVideo.kCVPixelBufferMetalCompatibilityKey
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSSelectorFromString
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.memcpy

/**
 * Presents frames into an `AVSampleBufferDisplayLayer`, on iOS and on macOS.
 *
 * This layer exists for one reason: it is the only content a picture in picture controller accepts
 * from a player that is not the system player. The Metal renderer stays the general path, and this
 * one is chosen when the application wants the small window.
 *
 * Frames arrive as pixel buffers. A hardware frame already is one and is retained rather than
 * copied. A software frame is copied into one, which costs a pass over the picture, so this
 * renderer earns its price only while the small window matters.
 *
 * The engine paces, not the layer. Every sample is marked to display at once, so the video
 * scheduler stays the single authority on when a frame shows. The layer can still carry a control
 * timebase: the picture in picture window reads its progress from that clock, and the mark keeps
 * the clock from holding frames back.
 *
 * Subtitles are drawn into the picture. The layer shows sample buffers and nothing else, and the
 * small window shows only the layer. So while an overlay has text, the picture and the text are
 * composed on the GPU into a new pixel buffer, and that buffer is shown instead. A subtitle change
 * redraws the picture on screen, so a paused picture gains or loses its text at once. Without a
 * Metal device the text is left out and the picture still shows.
 *
 * On macOS 14, iOS 17 and later, the layer's own video renderer takes the samples on the calling
 * thread. Before that, the layer takes them itself, on the main queue. Apple asks for one of the two
 * per layer, never both.
 *
 * Formats: NV12, planar 4:2:0 whose chroma is interleaved on the way in, and BGRA. Anything else
 * is refused, which the engine counts as a dropped frame rather than a failure.
 */
public class SampleBufferVideoRenderer internal constructor(
    private val resolve: MetalPictureResolver,
    private val sink: SampleSink,
    private val makeBurner: () -> SubtitleBurner? = { SubtitleBurner.createOrNull() },
) : VideoRenderer {

    public constructor(
        layer: AVSampleBufferDisplayLayer,
        resolve: MetalPictureResolver,
    ) : this(resolve = resolve, sink = sampleSinkFor(layer))

    private val presented = atomic(0L)
    private val failed = atomic(0L)
    private val closed = atomic(false)
    private val toneMapAnnounced = atomic(false)
    private val eventFlow = MutableSharedFlow<RendererEvent>(extraBufferCapacity = 8)

    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    /**
     * Guards everything below it. The engine presents from its video worker and publishes subtitles
     * from another thread, and both end in the sink, so the lock also keeps the samples in order.
     */
    private val lock = SynchronizedObject()
    private var overlay: SubtitleOverlay? = null
    private var burner: SubtitleBurner? = null
    private var burnerTried = false

    /** The last picture without text, one reference held, so a subtitle change can redraw it. */
    private var lastPicture: PlainPicture? = null

    /** True when the sample on screen carries burned-in text. */
    private var showingText = false

    /** Frames this renderer handed to the layer. */
    public val presentedFrames: Long get() = presented.value

    /** Frames it could not turn into a pixel buffer at all. */
    public val failedFrames: Long get() = failed.value

    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> =
        setOf(HwSurfaceKind.CoreVideoPixelBuffer)

    override fun supports(format: PlayerPixelFormat): Boolean = format in SUPPORTED

    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        val picture = frame.use { open ->
            if (closed.value) null else pixelBufferFor(open)?.let { PlainPicture(it, PictureFacts.of(open), targetNanos) }
        }
        if (picture == null) {
            failed.incrementAndGet()
            return false
        }
        val shown = synchronized(lock) {
            if (closed.value) {
                CVPixelBufferRelease(picture.buffer)
                false
            } else {
                lastPicture?.let { CVPixelBufferRelease(it.buffer) }
                lastPicture = picture
                show(picture)
            }
        }
        if (shown) presented.incrementAndGet() else failed.incrementAndGet()
        return shown
    }

    override fun vsyncIntervalNanos(): Long? = null

    /** The layer's bounds and gravity belong to whoever owns it, as with the Core Graphics path. */
    override fun setViewport(width: Int, height: Int, scale: Float): Unit = Unit

    /**
     * Keeps [overlay] for every frame from now on, and redraws the picture on screen when its text
     * changes, so the change shows while the video is paused too.
     */
    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        synchronized(lock) {
            this.overlay = overlay
            if (closed.value) return
            val last = lastPicture ?: return
            if (showingText || overlay.hasText()) show(last)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        synchronized(lock) {
            lastPicture?.let { CVPixelBufferRelease(it.buffer) }
            lastPicture = null
            burner?.close()
            burner = null
            sink.flushAndRemoveImage()
        }
    }

    /**
     * Builds the sample for [picture], with the text burned in while there is text, and hands it
     * to the sink. Called under [lock]. False when no sample could be built.
     */
    private fun show(picture: PlainPicture): Boolean {
        val text = overlay?.takeIf { it.hasText() }
        val burned = text?.let { burn(picture, it) }
        val sample = sampleBufferFor(burned ?: picture.buffer, picture.targetNanos)
        // The sample holds its own reference to the image, so the burned buffer's can go now.
        burned?.let { CVPixelBufferRelease(it) }
        if (sample == null) return false
        markDisplayImmediately(sample)
        sink.enqueue(sample)
        showingText = burned != null
        return true
    }

    /** The composed picture, or null to show it without text. Called under [lock]. */
    private fun burn(picture: PlainPicture, text: SubtitleOverlay): CVPixelBufferRef? {
        if (!burnerTried) {
            burnerTried = true
            burner = makeBurner()
        }
        val composed = burner?.burn(picture.buffer, picture.facts, text) ?: return null
        if (picture.facts.colorSpace.willToneMap() && toneMapAnnounced.compareAndSet(expect = false, update = true)) {
            eventFlow.tryEmit(RendererEvent.ToneMapEngaged(transfer = picture.facts.colorSpace.transfer.name))
        }
        return composed
    }

    /** A hardware frame is retained. A software frame is copied. Null refuses the frame. */
    private fun pixelBufferFor(frame: VideoFrame): CVPixelBufferRef? =
        when (val picture = resolve.resolve(frame)) {
            is MetalPicture.CorePixelBuffer -> CVPixelBufferRetain(picture.buffer.reinterpret())
            is MetalPicture.SoftwarePlanes -> copyIntoPixelBuffer(picture, frame.colorSpace.fullRange)
            null -> null
        }

    private fun sampleBufferFor(pixels: CVPixelBufferRef, targetNanos: Long): CMSampleBufferRef? =
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

    /** A picture as it left its frame: the pixels, the facts the composer needs, and its time. */
    private class PlainPicture(
        val buffer: CVPixelBufferRef,
        val facts: PictureFacts,
        val targetNanos: Long,
    )

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000
        val SUPPORTED = setOf(
            PlayerPixelFormat.Nv12,
            PlayerPixelFormat.Yuv420p,
            PlayerPixelFormat.Bgra,
        )
    }
}

private fun SubtitleOverlay?.hasText(): Boolean = this != null && images.isNotEmpty()

/**
 * Marks [sample] to show the moment it arrives, whatever time the layer's clock reads.
 *
 * The presentation times this renderer writes are host time. A layer with a control timebase at
 * the player's position would read them as far in the future and show nothing.
 */
internal fun markDisplayImmediately(sample: CMSampleBufferRef) {
    val attachments = CMSampleBufferGetSampleAttachmentsArray(sample, createIfNecessary = true) ?: return
    val first: CFMutableDictionaryRef = CFArrayGetValueAtIndex(attachments, 0)?.reinterpret() ?: return
    CFDictionarySetValue(first, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue)
}

/** Where the renderer's samples go. */
internal interface SampleSink {
    /** Shows [sample] as soon as possible. Takes over the caller's reference to it. */
    fun enqueue(sample: CMSampleBufferRef)

    /** Drops what is queued and takes the last picture off the layer. */
    fun flushAndRemoveImage()
}

/** The layer's own video renderer, which may be fed from any thread. */
internal class VideoRendererSink(private val renderer: AVSampleBufferVideoRenderer) : SampleSink {
    override fun enqueue(sample: CMSampleBufferRef) {
        renderer.enqueueSampleBuffer(sample)
        CFRelease(sample)
    }

    override fun flushAndRemoveImage() {
        renderer.flushWithRemovalOfDisplayedImage(true, null)
    }
}

/** The layer's older calls, deprecated from macOS 15 and iOS 18, made on the main queue. */
internal class LayerSink(
    private val layer: AVSampleBufferDisplayLayer,
    private val onMain: (block: () -> Unit) -> Unit = { block -> dispatch_async(dispatch_get_main_queue()) { block() } },
) : SampleSink {
    override fun enqueue(sample: CMSampleBufferRef) {
        onMain {
            layer.enqueueSampleBuffer(sample)
            CFRelease(sample)
        }
    }

    override fun flushAndRemoveImage() {
        onMain { layer.flushAndRemoveImage() }
    }
}

/** The layer's video renderer where the system has one (macOS 14, iOS 17), and the layer before. */
internal fun sampleSinkFor(layer: AVSampleBufferDisplayLayer): SampleSink =
    if (layer.respondsToSelector(NSSelectorFromString("sampleBufferRenderer"))) {
        VideoRendererSink(layer.sampleBufferRenderer)
    } else {
        LayerSink(layer)
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
        // An IOSurface is what makes the buffer presentable by a display layer at all, and Metal
        // compatibility is what lets the subtitle pass read it as a texture. Built in CoreFoundation
        // rather than Foundation so the keys stay the CFStrings the API declares.
        val surfaceProperties = CFDictionaryCreateMutable(
            null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        val attributes = CFDictionaryCreateMutable(
            null, 2, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr,
        )
        CFDictionarySetValue(attributes, kCVPixelBufferIOSurfacePropertiesKey, surfaceProperties)
        CFDictionarySetValue(attributes, kCVPixelBufferMetalCompatibilityKey, kCFBooleanTrue)
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
