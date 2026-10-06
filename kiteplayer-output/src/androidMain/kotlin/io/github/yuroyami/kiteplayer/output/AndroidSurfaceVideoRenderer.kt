package io.github.yuroyami.kiteplayer.output

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.view.Display
import android.view.Surface
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import io.github.yuroyami.kiteplayer.PictureCrop
import io.github.yuroyami.kiteplayer.VideoSize
import io.github.yuroyami.kiteplayer.spi.HwSurfaceKind
import kotlin.math.roundToInt
import io.github.yuroyami.kiteplayer.spi.PlayerPixelFormat
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.spi.RendererEvent
import io.github.yuroyami.kiteplayer.spi.SubtitleOverlay
import io.github.yuroyami.kiteplayer.spi.VideoFrame
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoRenderer
import io.github.yuroyami.kiteplayer.HdrPolicy
import io.github.yuroyami.kiteplayer.spi.ColorTransfer
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking

/**
 * Draws frames into a [Surface] the caller owns.
 *
 * The conversion from the decoder's pixel format to RGBA is supplied by the caller through [convert],
 * because it lives in whichever backend produced the frame. This module knows how to put pixels on a
 * Surface and nothing about how they were decoded, which is why the converter arrives as a function
 * rather than as a dependency.
 *
 * ### The Surface belongs to the caller
 *
 * The constructor stores the Surface and never calls `release()` on it, because it never owned it: it
 * belongs to whichever view, window or codec handed it over, and that owner releases it on its own
 * schedule. The one rule the caller has to keep is the other side of the same coin: close this
 * renderer before releasing or replacing the Surface. [close] waits for a lock or a post already in
 * flight, so once it returns nothing here will touch the Surface again. Releasing it first instead
 * leaves this renderer drawing into a dead handle, which on Android is not an exception but a native
 * abort.
 *
 * ### Why it has its own worker
 *
 * Converting a frame on the CPU and pushing it through a software canvas costs milliseconds, and at
 * 1080p enough of them that doing it inside [present] starves everything: the presentation schedule
 * slips, and with the process saturated the audio feeder cannot keep the device fed either. So
 * [present] hands the frame to a worker and returns at once, which is what the renderer contract
 * allows for exactly this reason.
 *
 * Only the newest frame is kept. When the renderer cannot keep up, a new frame replaces the one still
 * waiting rather than a queue building, which is the right trade for a slow renderer: the engine's
 * schedule stays intact and the picture updates as often as this renderer manages, instead of the
 * whole player being dragged down to its speed. The one slot is written out by hand rather than taken
 * from a conflated channel, because a conflated channel discards the displaced element silently, and a
 * silently discarded frame is a decoder buffer that is never closed and never counted. Owning the slot
 * means the displaced frame is closed and counted, which is both correct and visible in
 * [supersededFrames].
 *
 * ### One seam for Android graphics
 *
 * Every `Surface`, `Canvas` and `Bitmap` call sits behind [CanvasTarget], and everything above that
 * seam is ordinary Kotlin: the ownership rules, the byte validation, the swizzle and the geometry. That
 * is what lets the whole of this class be tested on a development machine, where the Android graphics
 * classes exist as stubs that throw the moment they are called. The real Surface is proved separately,
 * once, by a device test.
 *
 * ### The rules this obeys
 *
 * The frame belongs to this renderer from the moment [present] is called and is closed exactly once,
 * including when it is superseded before being drawn, when conversion fails, and when the renderer is
 * closed while it is still in hand. Losing the Surface is an event and not an exception: the renderer
 * refuses frames while it is gone, counts them, says so once through [events], and starts drawing again
 * by itself when a lock next succeeds. It never calls back into the player and never stops playback,
 * because a backgrounded window should not stop the sound.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
public class AndroidSurfaceVideoRenderer internal constructor(
    /** Converts a frame to tightly packed RGBA, one byte per component, no row padding. */
    private val convert: (VideoFrame) -> ByteArray,
    /** Where finished pictures go. Production locks a real Surface; a host test records the calls. */
    private val target: CanvasTarget,
    /** Present only for renderer generations that can negotiate direct MediaCodec output. */
    private val codecTarget: MediaCodecSurfaceTarget? = null,
    /** A separate UI layer used when MediaCodec owns the video Surface. */
    private val overlayConsumer: ((SubtitleOverlay?) -> Unit)? = null,
    /** Keeps the view geometry in step with both direct and software decoder output. */
    private val geometryConsumer: ((VideoSize, Int, PictureCrop?) -> Unit)? = null,
    /** Whether [convert] rolls this frame's HDR off to SDR. See the public constructors. */
    private val toneMapped: (VideoFrame) -> Boolean = { false },
) : VideoRenderer {

    /**
     * The renderer as a caller builds it: pictures are drawn into [surface] from this renderer's own
     * thread.
     *
     * [surface] is stored and never released here. Close this renderer before releasing or replacing
     * it.
     *
     * @param convert converts a frame to tightly packed RGBA, one byte per component, no row padding.
     */
    public constructor(
        surface: Surface,
        convert: (VideoFrame) -> ByteArray,
    ) : this(convert = convert, target = SurfaceCanvasTarget(surface))

    /**
     * Builds a renderer before its display Surface exists.
     *
     * Attach this renderer before opening media so its paired MediaCodec factory participates in
     * decoder selection, then forward every Surface lifecycle change through [setSurface]. Software
     * frames still use [convert] as a fallback. [onOverlay] should draw into a separate view above the
     * Surface because MediaCodec owns the video Surface while the direct path is active.
     *
     * [toneMapped] answers whether [convert] rolls a frame's HDR off to SDR, because only the
     * converter knows. The renderer then reports `RendererEvent.ToneMapEngaged`. The default
     * answers false, for a converter that never tone maps.
     *
     * [onVideoGeometry] hears the stored size, the turn and the crop of the pictures MediaCodec
     * writes straight into the Surface. Nothing draws those pictures, so the view that owns the
     * Surface is what hides the cropped edges (#497).
     */
    public constructor(
        convert: (VideoFrame) -> ByteArray,
        onOverlay: (SubtitleOverlay?) -> Unit,
        onVideoGeometry: (VideoSize, Int, PictureCrop?) -> Unit = { _, _, _ -> },
        toneMapped: (VideoFrame) -> Boolean = { false },
    ) : this(
        convert = convert,
        targets = AndroidSurfaceTargets(null, onVideoGeometry),
        overlayConsumer = onOverlay,
        toneMapped = toneMapped,
    )

    private constructor(
        convert: (VideoFrame) -> ByteArray,
        targets: AndroidSurfaceTargets,
        overlayConsumer: ((SubtitleOverlay?) -> Unit)? = null,
        toneMapped: (VideoFrame) -> Boolean,
    ) : this(
        convert = convert,
        target = targets.canvas,
        codecTarget = targets.codec,
        overlayConsumer = overlayConsumer,
        geometryConsumer = targets.geometryConsumer,
        toneMapped = toneMapped,
    )

    private val presented = atomic(0L)
    private val superseded = atomic(0L)
    private val failed = atomic(0L)
    private val closed = atomic(false)

    /** The single frame waiting to be drawn. Newest wins, and the displaced one is closed here. */
    private val pending = atomic<VideoFrame?>(null)

    /**
     * Orders the worker's take of a frame and its last look before drawing against [clearPicture]:
     * a frame taken under one [pictureEpoch] is not drawn once the epoch has moved on.
     */
    private val pictureLock = Any()

    /** Moved on by each [clearPicture]. Read and written only under [pictureLock]. */
    private var pictureEpoch = 0L

    /**
     * True from a clear until a picture of the current [pictureEpoch] is drawn. While it holds, a
     * subtitle change redraws the black canvas with the new cues, because no frame is coming to
     * carry them. Read and written only under [pictureLock].
     */
    private var pictureCleared = false

    /** The [pictureEpoch] whose clear the worker has shown. Worker thread only. */
    private var blankedEpoch = 0L

    /** The cues the worker last drew over the background, or null for none. Worker thread only. */
    private var blankedCues: SubtitleOverlay? = null

    /** The overlay to composite above the picture. Written by the engine, read by the worker. */
    private val overlay = atomic<SubtitleOverlay?>(null)

    /** The ruling scale mode; written by the engine, read at each draw. */
    private val scaleMode = atomic(io.github.yuroyami.kiteplayer.VideoScale.Fit)

    /** The ruling framing controls, under the same ownership as the scale mode. */
    private val videoTransform = atomic(io.github.yuroyami.kiteplayer.VideoTransform.Identity)

    /**
     * The engine's picture controls as Android's colour-matrix convention (offsets 0..255), or
     * null for neutral. Baked here, once per setting; the drawing thread hands the CURRENT value
     * to the target before each frame, and the target rebuilds its paint filter only on change.
     */
    private val videoColorMatrix = atomic<FloatArray?>(null)

    /** The size of the last canvas the worker locked. The overlay drawn into it covers it whole. */
    private val lastCanvasSize = atomic<VideoSize?>(null)

    /** The size a host gave through [setViewport], in pixels, or null before it says anything. */
    private val hostViewport = atomic<VideoSize?>(null)

    /** Wakes the worker. Conflated, so a signal sent before it waits is kept rather than lost. */
    private val signal = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** True between a lost Surface and the next successful post, so each transition is reported once. */
    private val surfaceIsLost = atomic(false)

    /**
     * The last few transitions are replayed, so a collector that attaches after the Surface went away
     * still learns that it did. A renderer that only ever spoke to whoever was already listening would
     * report surface loss to nobody in the one case it matters, which is a loss that happens at startup.
     */
    private val eventFlow = MutableSharedFlow<RendererEvent>(
        replay = 8,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<RendererEvent> = eventFlow.asSharedFlow()

    private val hdrAnnouncer = HdrAnnouncer { eventFlow.tryEmit(it) }

    /**
     * The ARGB pixels of the frame being drawn, kept between frames.
     *
     * A 1080p frame is 8.3 MB of `Int`, so allocating one per frame is 500 MB/s of garbage for the
     * collector to walk. It is replaced only when the pixel count changes, which happens when the track
     * changes and not otherwise. Only the worker touches it, and [close] releases it after joining that
     * worker, so a draw already running always finishes with the buffer it started with.
     */
    private var argb: IntArray = EMPTY_ARGB

    /**
     * The drawing thread, held so [close] can end it.
     *
     * `newSingleThreadContext` starts a real thread, and closing the dispatcher is the only thing that
     * stops it. A renderer that dropped this on the floor would leak one thread per instance, which for
     * a player that reopens its output on every track change is a thread per track.
     */
    private val dispatcher: CloseableCoroutineDispatcher = newSingleThreadContext("kiteplayer-surface-draw")

    private val worker = CoroutineScope(dispatcher + SupervisorJob())

    private val workerJob: Job = worker.launch {
        try {
            while (!closed.value) {
                signal.receive()
                drawPending()
                blankIfCleared()
            }
        } catch (_: ClosedReceiveChannelException) {
            // close() closed the signal channel. That is the ordinary way out of this loop, not a fault.
        }
    }

    /**
     * Frames the display showed. From Android 14 a hardware frame counts when MediaCodec reports it
     * rendered. Before that, and for every software frame, it counts when its picture was posted to
     * the Surface, which the display can still drop.
     */
    public val presentedFrames: Long get() = presented.value

    /**
     * Frames replaced in the waiting slot by a newer one before they could be drawn, or let go
     * because the picture was taken off before they were drawn.
     *
     * A non-zero count here means this renderer is the bottleneck, not the decoder and not the clock.
     * At 1080p it will be, because both the conversion and the draw are on the CPU. That is what a
     * hardware renderer fixes.
     */
    public val supersededFrames: Long get() = superseded.value

    /**
     * Frames that reached no Surface for a reason other than being superseded: a conversion that
     * failed or returned the wrong number of bytes, a frame refused while the Surface was gone, a lock
     * or a post the Surface would not give, a draw that threw, or a frame still in hand when the
     * renderer closed. From Android 14 it also counts a hardware frame the display dropped after its
     * release, because a newer frame was shown in its place.
     */
    public val failedFrames: Long get() = failed.value

    override fun videoDecoderFactories(): List<VideoDecoderFactory> =
        codecTarget?.let { target ->
            listOf(
                MediaCodecVideoDecoderFactory(
                    target,
                    // Read at each decoder's creation, so a policy change applies from the next open.
                    outputAdmission = MediaCodecOutputAdmission { requirement ->
                        directSurfaceOutputContract(requirement, toneMap = hdrPolicy.value == HdrPolicy.ToneMap)
                    },
                ),
            )
        }.orEmpty()

    private val hdrPolicy = atomic(HdrPolicy.Auto)

    /**
     * HDR from the decoder goes to the Surface as it is under [HdrPolicy.Auto], and the system shows
     * it as HDR on a display that can. Under [HdrPolicy.ToneMap] the next open asks MediaCodec for
     * SDR, and a software frame is tone mapped by the converter either way.
     */
    override fun setHdrPolicy(policy: HdrPolicy) {
        hdrPolicy.value = policy
    }

    /** What the display can show of HDR, fed by the view; null until it says. */
    private val displayHdr = atomic<DisplayHdr?>(null)

    private class DisplayHdr(val types: IntArray, val headroom: Float)

    /**
     * What the display this renderer's Surface is on can show of HDR: the `Display.HdrCapabilities`
     * types it supports and how far beyond standard white it goes now, or 1 when unknown. Fed by the
     * view, because a Surface does not know its display. Until it is fed, the renderer reports
     * nothing about HDR on the direct path, where only the display decides.
     */
    public fun setDisplayHdr(types: IntArray, headroom: Float) {
        displayHdr.value = DisplayHdr(types.copyOf(), headroom.coerceAtLeast(1f))
    }

    /**
     * True when HDR shows as HDR here (#447): under [HdrPolicy.Auto], on the direct codec path,
     * which hands the codec's HDR to the system, and on a display that says it supports PQ or HLG.
     * The software path tone maps every frame, and a display the view has not described yet counts
     * as standard range, so the variant choice keeps SDR until the view says otherwise.
     */
    override val showsHdr: Boolean
        get() = hdrPolicy.value == HdrPolicy.Auto && codecTarget != null && displayShowsHdr(displayHdr.value?.types)

    /**
     * False: a MediaCodec frame goes to the Surface and leaves no copy, so a paused picture takes a
     * change of its look only from the engine decoding it again (#463).
     */
    override val redrawsHeldPicture: Boolean get() = false

    /**
     * Says what happened to an HDR frame the codec sent to the Surface: tone mapped on request,
     * shown as HDR by a display that supports its transfer, or tone mapped by the system for one
     * that does not.
     */
    private fun announceDirectRange(frame: DirectSurfaceVideoFrame) {
        (frame as? MediaCodecBufferFrame)?.toneMappedFrom?.let {
            hdrAnnouncer.announce(it)
            return
        }
        val color = frame.colorSpace
        if (!color.isHdr) return
        val display = displayHdr.value ?: return
        val shown = when (color.transfer) {
            ColorTransfer.Pq -> display.types.any {
                it == Display.HdrCapabilities.HDR_TYPE_HDR10 ||
                    it == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS ||
                    it == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
            }
            else -> display.types.any { it == Display.HdrCapabilities.HDR_TYPE_HLG }
        }
        if (shown) {
            hdrAnnouncer.announceShown(color.transfer.name, display.headroom)
        } else {
            hdrAnnouncer.announce(color.transfer.name)
        }
    }

    /** MediaCodec buffers go straight to the Surface; every other format uses the software fallback. */
    override fun supportedHardwareSurfaces(): Set<HwSurfaceKind> =
        if (codecTarget != null) setOf(HwSurfaceKind.MediaCodecBuffer) else emptySet()

    override fun supports(format: PlayerPixelFormat): Boolean =
        format != PlayerPixelFormat.Opaque || codecTarget != null

    /**
     * Queues [frame] for drawing and returns immediately.
     *
     * Returns true because the frame was accepted for presentation, which is what the engine is asking.
     * Whether it is ultimately drawn or replaced by a newer one is this renderer's business, and is
     * reported through [presentedFrames] and [supersededFrames]. False means this frame will never be
     * drawn: the renderer is closed, or the Surface is gone. The frame is closed either way.
     */
    override suspend fun present(frame: VideoFrame, targetNanos: Long): Boolean {
        matchFrameRate(frame.pts.micros)
        if (closed.value) {
            frame.close()
            failed.incrementAndGet()
            return false
        }
        if (frame is DirectSurfaceVideoFrame) {
            if (frame.target !== codecTarget) {
                frame.close()
                failFrame("a MediaCodec frame belongs to a different Surface target")
                return false
            }
            val framePtsUs = frame.pts.micros
            // Where MediaCodec reports every shown frame, a frame counts as presented when the
            // display showed it, and one the display dropped counts as failed (#139).
            val exact = mediaCodecReportsEveryRender()
            val report = if (!exact) null else object : MediaCodecDisplayReport {
                override fun displayed(atNanos: Long) {
                    presented.incrementAndGet()
                    eventFlow.tryEmit(RendererEvent.FramePresented(Pts(framePtsUs), atNanos = atNanos, exact = true))
                }

                override fun lost() {
                    if (!closed.value) failed.incrementAndGet()
                }
            }
            val accepted = frame.renderAt(targetNanos, displayReport = report) { rendered ->
                if (rendered) {
                    noteSurfaceAvailable()
                    if (!exact) {
                        presented.incrementAndGet()
                        // Best effort before Android 14: the codec released the buffer toward the
                        // Surface, and the display may still drop it.
                        eventFlow.tryEmit(
                            RendererEvent.FramePresented(Pts(framePtsUs), atNanos = System.nanoTime(), exact = false),
                        )
                    }
                } else if (!closed.value) {
                    failed.incrementAndGet()
                }
            }
            if (!accepted) {
                failWithLostSurface("there is no live Surface for the MediaCodec frame")
            } else {
                announceDirectRange(frame)
            }
            return accepted
        }
        if (!targetIsValid()) {
            frame.close()
            failWithLostSurface("the Surface is no longer valid")
            return false
        }
        // Replace whatever was waiting. The displaced frame is closed and counted here, because nothing
        // else will ever see it.
        val displaced = pending.getAndSet(frame)
        if (displaced != null) {
            displaced.close()
            superseded.incrementAndGet()
        }
        // Read `closed` again, after the store. close() writes it before it drains the slot, so between
        // the two of them at least one sees the other's work and the frame is never left in the slot
        // with no worker alive to take it.
        if (closed.value) {
            drainPending()
            return false
        }
        signal.trySend(Unit)
        return true
    }

    /** Draws whatever is waiting, if anything. Worker thread only. */
    private fun drawPending() {
        var epoch = 0L
        val frame = synchronized(pictureLock) {
            epoch = pictureEpoch
            pending.getAndSet(null)
        } ?: return
        val framePts = frame.pts
        val size = frame.size
        val rotation = quarterTurn(frame.rotationDegrees)
        val mirrored = frame.mirrored
        val crop = frame.crop
        // This renderer cuts the crop itself, so the view hears the shape of what is left and no
        // crop of its own to apply.
        geometryConsumer?.invoke(size.cropped(crop), rotation, null)
        if (toneMapped(frame)) hdrAnnouncer.announce(frame.colorSpace.transfer.name)
        val converted = try {
            convert(frame)
        } catch (failure: Throwable) {
            failFrame(failure.message ?: "the converter failed")
            null
        } finally {
            // Ownership ends here. Everything below works on bytes this renderer owns.
            frame.close()
        }
        val picture = converted?.let { swizzle(it, size) } ?: return
        if (synchronized(pictureLock) { pictureEpoch != epoch }) {
            // The picture was taken off while this frame was converted (#530).
            superseded.incrementAndGet()
            return
        }
        draw(picture, size, rotation, mirrored, framePts, crop, epoch)
    }

    /**
     * Takes the picture off (#530): the frame waiting for the worker goes, a frame the worker is
     * converting is not drawn, and the worker blanks the Surface. Nothing here waits, because the
     * engine calls this from its own loop.
     */
    override fun clearPicture() {
        if (closed.value) return
        synchronized(pictureLock) {
            pending.getAndSet(null)?.let { waiting ->
                waiting.close()
                superseded.incrementAndGet()
            }
            pictureEpoch += 1
            pictureCleared = true
        }
        signal.trySend(Unit)
    }

    /**
     * Shows the background while the picture is off: once for each clear, and again for each change
     * of the cues this renderer draws itself. Worker thread only.
     */
    private fun blankIfCleared() {
        var epoch = 0L
        val cleared = synchronized(pictureLock) {
            epoch = pictureEpoch
            pictureCleared
        }
        if (!cleared) return
        val cues = if (overlayConsumer == null) overlay.value?.takeIf { it.images.isNotEmpty() } else null
        val fresh = epoch != blankedEpoch
        if (!fresh && cues === blankedCues) return
        blankedEpoch = epoch
        blankedCues = cues
        drawBlank(fresh, cues)
    }

    /**
     * Shows the background in place of a picture taken off. Worker thread only.
     *
     * A Surface that a MediaCodec decoder may write into again, with the cues drawn in a view of
     * their own, is blanked through EGL at a [fresh] clear, because EGL lets go of the Surface
     * afterwards and a canvas never does: a decoder can never take a Surface a canvas has held. When
     * EGL cannot take it, which is what happens once a canvas holds it already, and on every other
     * Surface, this draws a black canvas with [cues] over it. A Surface that will not lock is left
     * as it is: no frame was lost, and the next one says so.
     */
    private fun drawBlank(fresh: Boolean, cues: SubtitleOverlay?) {
        if (!targetIsValid()) return
        if (fresh && codecTarget != null && overlayConsumer != null && blankTarget()) return
        val canvas = runCatching { target.lock() }.getOrNull() ?: return
        try {
            canvas.clearToBlack()
            noteCanvasSize(canvas.width, canvas.height)
            cues?.let { drawOverlay(canvas, it) }
        } catch (_: Throwable) {
            // Nothing to count: no frame was being drawn.
        } finally {
            runCatching { target.post(canvas) }
        }
    }

    /**
     * Turns tightly packed RGBA bytes into the ARGB integers a bitmap wants, or counts the frame as
     * failed and returns null.
     *
     * The two orders are easy to confuse and the mistake is invisible in a grey test pattern: Android
     * bitmaps are ARGB integers, whose bytes on a little-endian machine are stored BGRA, while the
     * converter hands over RGBA in address order. Reading the bytes as an integer therefore swaps red
     * and blue, and the picture comes out with skies orange and skin blue. Building each integer from
     * named components instead cannot be got wrong by accident, and the byte order of the machine stops
     * mattering.
     *
     * The size check is the other half. A converter that returns fewer bytes than the frame needs is a
     * bug in the converter, and the choice here is between a typed failure and a picture drawn from
     * whatever was in the buffer beyond the end of the data. Anything other than exactly one tightly
     * packed RGBA quad per pixel is counted, reported and refused, and no partial picture is ever
     * drawn.
     *
     * The alpha byte is not read. Video frames are opaque, the canvas underneath was cleared to opaque
     * black, and a converter that writes a zero alpha would otherwise draw nothing at all onto it. This
     * is the same choice the Apple fallback makes when it asks Core Graphics to skip the alpha channel.
     */
    private fun swizzle(rgba: ByteArray, size: VideoSize): IntArray? {
        val width = size.width
        val height = size.height
        if (width <= 0 || height <= 0) {
            failFrame("a ${width}x$height frame has no pixels to draw")
            return null
        }
        val pixels = width.toLong() * height.toLong()
        if (pixels > Int.MAX_VALUE.toLong()) {
            failFrame("a ${width}x$height frame is larger than one buffer can hold")
            return null
        }
        val required = pixels * RGBA_BYTES_PER_PIXEL
        if (rgba.size.toLong() != required) {
            failFrame("the converter returned ${rgba.size} bytes for a ${width}x$height frame, which needs $required")
            return null
        }

        val count = pixels.toInt()
        val buffer = argbBuffer(count)
        var at = 0
        for (index in 0 until count) {
            val red = rgba[at].toInt() and 0xFF
            val green = rgba[at + 1].toInt() and 0xFF
            val blue = rgba[at + 2].toInt() and 0xFF
            buffer[index] = OPAQUE_ALPHA or (red shl 16) or (green shl 8) or blue
            at += RGBA_BYTES_PER_PIXEL.toInt()
        }
        return buffer
    }

    /** The reusable ARGB buffer, replaced only when the pixel count changes. Worker thread only. */
    private fun argbBuffer(pixels: Int): IntArray {
        val existing = argb
        if (existing.size == pixels) return existing
        val replacement = IntArray(pixels)
        argb = replacement
        return replacement
    }

    /**
     * Locks the Surface, draws one picture and always gives the canvas back. Worker thread only.
     *
     * The `finally` is the load-bearing part. A locked canvas holds a buffer that belongs to the
     * Surface, and a lock that is not posted is never released: the next lock blocks or fails, and the
     * picture freezes on whatever was last drawn. So a draw that throws still reaches the post, and only
     * then is the failure counted.
     *
     * Losing the Surface is not a failure of the renderer. It is counted against the frame, reported
     * once as a transition, and then the worker carries on: the next lock that succeeds says so and
     * drawing resumes. Nothing here calls the player.
     */
    private fun draw(
        picture: IntArray,
        size: VideoSize,
        rotationDegrees: Int,
        mirrored: Boolean,
        framePts: Pts,
        crop: PictureCrop?,
        epoch: Long,
    ) {
        if (!targetIsValid()) {
            failWithLostSurface("the Surface went away before a canvas could be locked")
            return
        }
        val canvas = try {
            target.lock()
        } catch (refusal: Throwable) {
            failWithLostSurface(refusal.message ?: "the Surface refused a canvas")
            return
        }
        if (canvas == null) {
            failWithLostSurface("the Surface refused a canvas")
            return
        }

        var drawFailure: Throwable? = null
        var postFailure: Throwable? = null
        try {
            // Everything outside the picture is black, and opaquely so: the buffer that comes back from
            // a lock holds whatever was drawn into it two frames ago, and a letterbox that is not
            // cleared shows it.
            canvas.clearToBlack()
            noteCanvasSize(canvas.width, canvas.height)
            target.setVideoColorMatrix(videoColorMatrix.value)
            val layout = frameLayout(
                canvas.width, canvas.height, size, rotationDegrees, scaleMode.value,
                videoTransform.value, mirrored, crop,
            )
            if (layout == null) {
                drawFailure = IllegalStateException(
                    "a ${size.width}x${size.height} frame has no place on a ${canvas.width}x${canvas.height} canvas",
                )
            } else {
                canvas.drawFrame(picture, size.width, size.height, layout)
                overlay.value?.let { active ->
                    if (active.images.isNotEmpty()) drawOverlay(canvas, active)
                }
            }
        } catch (failure: Throwable) {
            drawFailure = failure
        } finally {
            try {
                target.post(canvas)
            } catch (failure: Throwable) {
                postFailure = failure
            }
        }

        // A post that throws is the Surface going away underneath a draw that had already started, so it
        // is reported as the loss it is rather than as a fault of this renderer.
        if (postFailure != null) {
            failWithLostSurface(postFailure.message ?: "the Surface refused the finished picture")
            return
        }
        if (drawFailure != null) {
            failFrame(drawFailure.message ?: "drawing the picture failed")
            return
        }
        presented.incrementAndGet()
        // A clear that came while this was drawn keeps the cleared state for the blank that follows.
        synchronized(pictureLock) { if (pictureEpoch == epoch) pictureCleared = false }
        noteSurfaceAvailable()
        // Best effort by design: the canvas was posted, the closest this CPU path can observe.
        eventFlow.tryEmit(
            RendererEvent.FramePresented(framePts, atNanos = System.nanoTime(), exact = false),
        )
    }

    /**
     * Draws the overlay over the whole canvas, rule 1 of docs/subtitle-placement.md: its viewport
     * maps onto the canvas, each axis on its own. The picture's fit, turn, zoom and pan play no
     * part, because the engine laid the text out upright for this canvas, which [outputSize]
     * reports.
     */
    private fun drawOverlay(canvas: TargetCanvas, active: SubtitleOverlay) {
        if (active.viewportWidth <= 0 || active.viewportHeight <= 0) return
        val scaleX = canvas.width.toFloat() / active.viewportWidth
        val scaleY = canvas.height.toFloat() / active.viewportHeight
        for ((imageIndex, image) in active.images.withIndex()) {
            canvas.drawOverlayImage(
                rgba = image.bitmap.pixels,
                width = image.bitmap.width,
                height = image.bitmap.height,
                left = image.x * scaleX,
                top = image.y * scaleY,
                drawWidth = image.bitmap.width * scaleX,
                drawHeight = image.bitmap.height * scaleY,
                contentHash = active.contentHash,
                imageIndex = imageIndex,
            )
        }
    }

    /** Remembers the canvas size for [outputSize], allocating only when the size changes. */
    private fun noteCanvasSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val known = lastCanvasSize.value
        if (known == null || known.width != width || known.height != height) {
            lastCanvasSize.value = VideoSize(width, height)
        }
    }

    /** [CanvasTarget.isValid] must never be the reason a frame is lost, so a throwing one reads false. */
    private fun targetIsValid(): Boolean = try {
        target.isValid()
    } catch (_: Throwable) {
        false
    }

    /** [CanvasTarget.blank], where a throw reads as false and the caller draws a black canvas instead. */
    private fun blankTarget(): Boolean = try {
        target.blank(SURFACE_FENCE_TIMEOUT_MS)
    } catch (_: Throwable) {
        false
    }

    /** Counts the frame and reports the loss, once per transition. */
    private fun failWithLostSurface(detail: String) {
        failed.incrementAndGet()
        reportSurfaceLost(detail)
    }

    /** Reports the loss once per transition, without counting a frame. */
    private fun reportSurfaceLost(detail: String) {
        if (surfaceIsLost.compareAndSet(expect = false, update = true)) {
            eventFlow.tryEmit(RendererEvent.SurfaceLost(detail))
        }
    }

    /** The first post to succeed after a loss says so, once. */
    private fun noteSurfaceAvailable() {
        if (surfaceIsLost.compareAndSet(expect = true, update = false)) {
            eventFlow.tryEmit(RendererEvent.SurfaceAvailable)
        }
    }

    /** Counts the frame and reports why. The Surface is fine; this frame was not. */
    private fun failFrame(detail: String) {
        failed.incrementAndGet()
        eventFlow.tryEmit(RendererEvent.Failed(detail))
    }

    /** Closes and counts a frame nobody will draw. */
    private fun drainPending() {
        val stranded = pending.getAndSet(null) ?: return
        stranded.close()
        failed.incrementAndGet()
    }

    override fun vsyncIntervalNanos(): Long? = displayVsyncNanos

    /**
     * The display's interval, fed by whoever owns the Surface: the renderer itself cannot ask,
     * because a Surface does not know its display. `KitePlayerView` in `kiteplayer-view` calls
     * this from the view's own display and again on every surface change, and a change while
     * attached is announced as [RendererEvent.VsyncChanged].
     */
    @Volatile
    private var displayVsyncNanos: Long? = null

    /**
     * The OS's own frame-rate matching (API 30+): the display can switch to the video's cadence.
     * The rate comes from the frames themselves, because the renderer is handed frames and no track
     * metadata. It is asked for once per Surface, and a new Surface starts a new request.
     */
    @Volatile private var matchedSurface: Surface? = null
    @Volatile private var frameRateRequest = SurfaceFrameRateRequest()

    private fun matchFrameRate(ptsUs: Long) {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val surface = matchedSurface ?: return
        val fps = frameRateRequest.offer(ptsUs) ?: return
        runCatching {
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
        }
    }

    public fun setDisplayRefreshRate(hz: Float) {
        val nanos = if (hz > 0f) (1_000_000_000.0 / hz).toLong() else null
        val changed = nanos != displayVsyncNanos
        displayVsyncNanos = nanos
        if (changed && nanos != null) eventFlow.tryEmit(RendererEvent.VsyncChanged(nanos))
    }

    /**
     * The size of the surface the overlay is drawn on, from its host. `KitePlayerView` gives the
     * size of its subtitle layer here, which only the host knows while the codec owns the video
     * Surface.
     */
    override fun setViewport(width: Int, height: Int, scale: Float) {
        val pixelWidth = (width * scale).roundToInt()
        val pixelHeight = (height * scale).roundToInt()
        hostViewport.value = if (pixelWidth > 0 && pixelHeight > 0) VideoSize(pixelWidth, pixelHeight) else null
    }

    /**
     * The size the engine lays subtitles out for, rule 2 of docs/subtitle-placement.md. When a
     * separate layer draws them, it is the size the host gave through [setViewport]. Otherwise it
     * is the canvas this renderer draws them into, known from the first frame, and the host's size
     * before that.
     */
    override val outputSize: VideoSize?
        get() = if (overlayConsumer != null) hostViewport.value else lastCanvasSize.value ?: hostViewport.value

    override fun setScaleMode(mode: io.github.yuroyami.kiteplayer.VideoScale) {
        scaleMode.value = mode
    }

    override fun setAdjustments(adjustments: io.github.yuroyami.kiteplayer.VideoAdjustments) {
        videoColorMatrix.value = if (adjustments.isIdentity) {
            null
        } else {
            // The engine's unit-domain law respelled into Android's convention: the translation
            // column moves to the 0..255 domain, everything else is the same matrix.
            adjustments.toColorMatrix().also { values ->
                values[4] *= 255f
                values[9] *= 255f
                values[14] *= 255f
                values[19] *= 255f
            }
        }
        // Applied when the next frame draws. A paused picture is decoded again by the engine to
        // show it (#463), because this renderer holds no drawn-frame copy to repaint, the same as its
        // paused-overlay behaviour. KiteVideo repaints immediately.
    }

    override fun setTransform(transform: io.github.yuroyami.kiteplayer.VideoTransform) {
        videoTransform.value = transform
        // Applied at the next drawn frame, the same recorded paused-picture limit as above.
    }

    /**
     * Stores the overlay for the worker to composite above every following picture. The engine
     * publishes on cue edges, so a cue appears with the next frame drawn after it, at most one
     * frame interval late, which at 30 fps is inside anyone's reading reaction.
     */
    override suspend fun setOverlay(overlay: SubtitleOverlay?) {
        val external = overlayConsumer
        if (external != null) {
            // A delegated overlay is the external layer's alone: storing it
            // here too made the software path burn every cue into the video AND hand it to the
            // view, so each subtitle drew twice, once without the view's rotation mapping.
            this.overlay.value = null
            external(overlay)
        } else {
            this.overlay.value = overlay
            signal.trySend(Unit)
        }
    }

    /**
     * Replaces the display Surface without replacing this renderer or its paired decoder.
     *
     * Replacing a non-null Surface never waits for a frame to finish drawing. The drawing thread
     * picks it up at its next lock. Passing null waits until no canvas
     * is locked on the old Surface, which `SurfaceHolder.Callback.surfaceDestroyed` requires, but for
     * one second at most: a lock can itself be waiting on the main thread, and then the wait would
     * never end. Giving up is reported as [RendererEvent.SurfaceLost].
     */
    public fun setSurface(surface: Surface?) {
        val directTarget = codecTarget
            ?: throw IllegalStateException("this renderer was built with a test CanvasTarget")
        if (closed.value) return
        // A new surface has no matched rate yet; the next frames measure it again (API 30+).
        // A fresh object, not a reset: the schedule thread may be inside the old one right now.
        matchedSurface = surface
        frameRateRequest = SurfaceFrameRateRequest()
        runCatching { directTarget.update(surface) }
            .onFailure { failure ->
                eventFlow.tryEmit(
                    RendererEvent.Failed(
                        failure.message ?: "MediaCodec could not switch its output Surface",
                    ),
                )
            }
        // A hop onto the drawing thread here once waited behind every frame still to be drawn,
        // for as long as frames kept coming, and Android reported the app as not responding.
        if (surface != null) return
        val switching = target as? SwitchingSurfaceCanvasTarget ?: return
        if (!switching.fence(SURFACE_FENCE_TIMEOUT_MS)) {
            reportSurfaceLost("a draw still held the Surface $SURFACE_FENCE_TIMEOUT_MS ms after it was destroyed")
        }
    }

    /**
     * Stops drawing and gives everything back except the Surface, which was never this renderer's.
     *
     * The order is the whole point, and every step is there because leaving it out loses something:
     * mark closed first so [present] stops accepting frames, close the signal so the worker's wait ends,
     * then cancel and join the worker so a lock or a post already in flight finishes and the frame it
     * was drawing is closed. Only then is the slot drained, which is final because nothing is left
     * running to refill it, and only then are the pixel buffers and the drawing thread released.
     *
     * This blocks the caller until the worker is done, and that is safe from any thread including the
     * one that runs the user interface: every canvas call this renderer makes is made on its own
     * private thread, so nothing it waits for can be waiting on the caller.
     */
    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        codecTarget?.let { directTarget ->
            directTarget.clearGeometryConsumer()
            runCatching { directTarget.update(null) }
        }
        overlayConsumer?.invoke(null)
        signal.close()
        worker.cancel()
        runBlocking { workerJob.join() }
        drainPending()
        argb = EMPTY_ARGB
        target.release()
        dispatcher.close()
    }

    private companion object {
        private const val RGBA_BYTES_PER_PIXEL: Long = 4L
        private const val OPAQUE_ALPHA: Int = 0xFF shl 24
        private val EMPTY_ARGB: IntArray = IntArray(0)

        /** A draw holds its canvas for tens of milliseconds, so only a stuck one outlasts this. */
        private const val SURFACE_FENCE_TIMEOUT_MS: Long = 1_000L
    }
}

/**
 * True when a display of `Display.HdrCapabilities` [types] shows HDR as HDR (#447): HDR10, HDR10+,
 * Dolby Vision or HLG. Null, a display nobody described, counts as standard range.
 */
internal fun displayShowsHdr(types: IntArray?): Boolean = types?.any {
    it == Display.HdrCapabilities.HDR_TYPE_HDR10 ||
        it == Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS ||
        it == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION ||
        it == Display.HdrCapabilities.HDR_TYPE_HLG
} == true

/** One shared lifecycle target for the direct codec producer and the software Canvas fallback. */
private class AndroidSurfaceTargets(
    initialSurface: Surface?,
    val geometryConsumer: ((VideoSize, Int, PictureCrop?) -> Unit)? = null,
) {
    val codec = MediaCodecSurfaceTarget(initialSurface, geometryConsumer)
    val canvas: CanvasTarget = SwitchingSurfaceCanvasTarget(codec)
}

/**
 * Lazily swaps the software fallback's Canvas target on the renderer worker.
 *
 * [isValid] only reads the shared lifecycle state. Every delegate change happens under [inUse],
 * which the drawing thread holds from [lock] to [post], so a Surface callback cannot recycle a
 * bitmap or replace a delegate underneath an active draw. [fence] waits on that lock and nothing
 * else, never on the drawing thread's queue of frames.
 */
internal class SwitchingSurfaceCanvasTarget(
    private val source: MediaCodecSurfaceTarget,
    /** Production wraps the real Surface; a host test passes a scripted target. */
    private val createDelegate: (Surface) -> CanvasTarget = ::SurfaceCanvasTarget,
    /** Production blanks through EGL; a host test records the call. */
    private val blankSurface: (Surface) -> Boolean = ::blankThroughEgl,
) : CanvasTarget {
    /** Fair, so a waiting [fence] goes next instead of being overtaken by the next frame's lock. */
    private val inUse = ReentrantLock(true)
    private var version: Long = Long.MIN_VALUE
    private var delegate: CanvasTarget? = null
    private var lockedDelegate: CanvasTarget? = null

    /** Kept here because every Surface gets a fresh delegate, and each one must be told. */
    private var videoColorMatrix: FloatArray? = null

    override fun isValid(): Boolean = source.snapshot().isDisplayable

    override fun setVideoColorMatrix(matrix: FloatArray?) {
        inUse.withLock {
            videoColorMatrix = matrix
            (lockedDelegate ?: delegate)?.setVideoColorMatrix(matrix)
        }
    }

    override fun lock(): TargetCanvas? {
        inUse.lock()
        val canvas = try {
            refresh()
            delegate?.let { active ->
                active.setVideoColorMatrix(videoColorMatrix)
                active.lock()?.also { lockedDelegate = active }
            }
        } catch (failure: Throwable) {
            inUse.unlock()
            throw failure
        }
        // A canvas handed out keeps the lock until post gives it back.
        if (canvas == null) inUse.unlock()
        return canvas
    }

    override fun post(canvas: TargetCanvas) {
        val active = lockedDelegate ?: error("no Canvas is locked")
        try {
            active.post(canvas)
        } finally {
            lockedDelegate = null
            inUse.unlock()
        }
    }

    /**
     * Waits up to [timeoutMillis] until no canvas is locked, then follows the published Surface.
     * False means a draw still holds one; the drawing thread follows at its next lock instead.
     */
    fun fence(timeoutMillis: Long): Boolean {
        if (!inUse.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        try {
            refresh()
        } finally {
            inUse.unlock()
        }
        return true
    }

    /**
     * Fills the published Surface with opaque black through EGL and lets go of it again (#530),
     * once no canvas is locked, waiting up to [timeoutMillis] for that. A canvas stays connected to
     * a Surface after its post, and a decoder can then never write into it, so a Surface shared with
     * a MediaCodec decoder is cleared this way. False when a draw still holds a canvas, when no
     * Surface is displayable, or when EGL cannot take it, as when a canvas already holds it; the
     * caller then draws a black canvas.
     */
    override fun blank(timeoutMillis: Long): Boolean {
        if (!inUse.tryLock(timeoutMillis, TimeUnit.MILLISECONDS)) return false
        try {
            refresh()
            val snapshot = source.snapshot()
            val surface = snapshot.surface?.takeIf { snapshot.isDisplayable } ?: return false
            return blankSurface(surface)
        } finally {
            inUse.unlock()
        }
    }

    /** Only with [inUse] held. */
    private fun refresh() {
        check(lockedDelegate == null) { "cannot replace a Surface while its Canvas is locked" }
        val snapshot = source.snapshot()
        if (snapshot.version == version) return
        delegate?.release()
        delegate = snapshot.surface?.takeIf { snapshot.isDisplayable }?.let(createDelegate)
        version = snapshot.version
    }

    override fun release() {
        inUse.withLock {
            check(lockedDelegate == null) { "cannot release a Surface while its Canvas is locked" }
            delegate?.release()
            delegate = null
            version = Long.MIN_VALUE
        }
    }
}

/**
 * The one place Android graphics are reached from.
 *
 * A locked canvas, a bitmap and a Surface cannot be had on a development machine: the classes exist
 * there as stubs whose every method throws. Putting them behind this interface is what lets the frame
 * ownership, the byte validation, the swizzle and the geometry above it be pinned by ordinary host
 * tests, and leaves exactly one implementation that needs a real device to prove.
 *
 * The lock and the post are separate calls rather than one block, deliberately: the renderer owns the
 * rule that a successful lock always reaches a post, and a rule the seam kept would be a rule only the
 * device could test.
 */
internal interface CanvasTarget {

    /** False once the Surface is gone. Asked before every lock, and cheap enough to ask that often. */
    fun isValid(): Boolean

    /** Locks and returns a canvas, or null when the target would not give one. */
    fun lock(): TargetCanvas?

    /**
     * The colour matrix to draw VIDEO pixels through (Android's 4x5, offsets 0..255), or null
     * for none. Applies to [TargetCanvas.drawFrame] only, never to overlay images; the engine
     * hands the current value before each draw and a target rebuilds its filter only on change.
     * Defaulted so the geometry test doubles keep compiling unfiltered.
     */
    fun setVideoColorMatrix(matrix: FloatArray?) {}

    /** Posts whatever was drawn into [canvas] and gives the lock back. */
    fun post(canvas: TargetCanvas)

    /**
     * Fills the Surface with black without a canvas, so a decoder can still write into it
     * afterwards (#530), waiting up to [timeoutMillis] for a draw in flight. False when this target
     * cannot, and the caller draws a black canvas instead.
     */
    fun blank(timeoutMillis: Long): Boolean = false

    /** Releases the drawing storage this target owns. Never the Surface, which belongs to the caller. */
    fun release()
}

/** One locked canvas, for the length of one frame. */
internal interface TargetCanvas {

    /** The canvas size in pixels, which is the output size the picture is fitted to. */
    val width: Int
    val height: Int

    /** Fills the whole canvas with opaque black, so the letterbox is black and last frame's is gone. */
    fun clearToBlack()

    /**
     * Draws [argb] ([sourceWidth] by [sourceHeight] pixels, row major) where [layout] says, turned by
     * [FrameLayout.rotationDegrees] about the destination centre, and mirrored first when
     * [FrameLayout.mirrored] says so. Only the layout's source rectangle of the bitmap is drawn.
     */
    fun drawFrame(argb: IntArray, sourceWidth: Int, sourceHeight: Int, layout: FrameLayout)

    /**
     * Composites one subtitle image above whatever [drawFrame] painted, into the rectangle given
     * in canvas pixels and never turned. [rgba] is premultiplied RGBA, as the cue contract says;
     * the production target uploads it as it is and caches it by [contentHash], so an unchanged
     * overlay uploads nothing.
     */
    fun drawOverlayImage(
        rgba: ByteArray,
        width: Int,
        height: Int,
        left: Float,
        top: Float,
        drawWidth: Float,
        drawHeight: Float,
        contentHash: Long,
        imageIndex: Int,
    )
}

/**
 * The production target: a real [Surface], a real [Canvas] and one reusable bitmap.
 *
 * The bitmap is the expensive object here, so it is kept between frames and replaced only when the
 * frame's stored dimensions change. That replacement is safe without any locking because every call
 * into this class comes from the renderer's single drawing thread, so the previous draw has always
 * finished before the next one asks for a bitmap.
 */
internal class SurfaceCanvasTarget(private val surface: Surface) : CanvasTarget {

    /** Filtered because the picture is almost always scaled, and a nearest sample of a scaled frame shimmers. */
    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
        isDither = false
    }

    /**
     * The video's own paint, split from [paint] so the picture controls never touch subtitles.
     * The filter is rebuilt only when the engine hands a DIFFERENT matrix reference, which is
     * once per user setting, not once per frame.
     */
    private val videoPaint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
        isDither = false
    }
    private var appliedColorMatrix: FloatArray? = null

    override fun setVideoColorMatrix(matrix: FloatArray?) {
        if (matrix === appliedColorMatrix) return
        appliedColorMatrix = matrix
        videoPaint.colorFilter = matrix?.let { android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(it)) }
    }

    private var bitmap: Bitmap? = null

    /** Reused so that drawing a frame allocates nothing at all. */
    private val destination = RectF()
    private val sourceRect = Rect()

    /** Uploaded overlay images, keyed by the overlay's contentHash, then by image index. */
    private var overlayHash: Long = Long.MIN_VALUE
    private val overlayBitmaps = mutableListOf<Bitmap>()

    /**
     * Cue pixels arrive PREMULTIPLIED (the RgbaBitmap contract since the 2026-08-17 audit) and
     * an ARGB_8888 bitmap stores premultiplied, so the upload is a raw copy. The old path here
     * premultiplied by hand and then let setPixels premultiply AGAIN, which turned every
     * antialiased edge and translucent cue darker with each pass.
     * Done once per contentHash and image index.
     */
    private fun overlayBitmapFor(
        rgba: ByteArray,
        width: Int,
        height: Int,
        contentHash: Long,
        imageIndex: Int,
    ): Bitmap {
        if (contentHash != overlayHash) {
            overlayBitmaps.forEach { it.recycle() }
            overlayBitmaps.clear()
            overlayHash = contentHash
        }
        overlayBitmaps.getOrNull(imageIndex)?.let { return it }
        check(imageIndex == overlayBitmaps.size) { "overlay images must be drawn in index order" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(rgba))
        overlayBitmaps += bitmap
        return bitmap
    }

    override fun isValid(): Boolean = surface.isValid

    override fun lock(): TargetCanvas? {
        // Null means the whole canvas is redrawn, which it is: every frame clears and refills it.
        val canvas = surface.lockCanvas(null) ?: return null
        return SurfaceTargetCanvas(canvas)
    }

    override fun post(canvas: TargetCanvas) {
        surface.unlockCanvasAndPost((canvas as SurfaceTargetCanvas).canvas)
    }

    override fun release() {
        bitmap?.recycle()
        bitmap = null
        overlayBitmaps.forEach { it.recycle() }
        overlayBitmaps.clear()
    }

    private fun bitmapFor(width: Int, height: Int): Bitmap {
        val existing = bitmap
        if (existing != null && !existing.isRecycled && existing.width == width && existing.height == height) {
            return existing
        }
        existing?.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap = it }
    }

    private inner class SurfaceTargetCanvas(val canvas: Canvas) : TargetCanvas {

        override val width: Int get() = canvas.width

        override val height: Int get() = canvas.height

        override fun clearToBlack() {
            // SRC rather than the default, so the buffer's old contents are replaced and not blended
            // with. A canvas from a lock is recycled memory, not a fresh page.
            canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC)
        }

        override fun drawFrame(argb: IntArray, sourceWidth: Int, sourceHeight: Int, layout: FrameLayout) {
            val picture = bitmapFor(sourceWidth, sourceHeight)
            picture.setPixels(argb, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
            destination.set(layout.drawLeft, layout.drawTop, layout.drawRight, layout.drawBottom)
            val saved = canvas.save()
            try {
                // Positive degrees are clockwise on a canvas, whose y axis points down, and the turn is
                // about the destination centre so that the drawn rectangle lands back on it.
                if (layout.rotationDegrees != 0) {
                    canvas.rotate(layout.rotationDegrees.toFloat(), layout.centerX, layout.centerY)
                }
                // Set after the turn, so it applies to the bitmap before the turn does.
                if (layout.mirrored) canvas.scale(-1f, 1f, layout.centerX, layout.centerY)
                val source = if (layout.cropsSource(sourceWidth, sourceHeight)) {
                    sourceRect.apply {
                        set(layout.sourceLeft, layout.sourceTop, layout.sourceRight, layout.sourceBottom)
                    }
                } else {
                    null
                }
                canvas.drawBitmap(picture, source, destination, videoPaint)
            } finally {
                canvas.restoreToCount(saved)
            }
        }

        override fun drawOverlayImage(
            rgba: ByteArray,
            width: Int,
            height: Int,
            left: Float,
            top: Float,
            drawWidth: Float,
            drawHeight: Float,
            contentHash: Long,
            imageIndex: Int,
        ) {
            val bitmap = overlayBitmapFor(rgba, width, height, contentHash, imageIndex)
            destination.set(left, top, left + drawWidth, top + drawHeight)
            canvas.drawBitmap(bitmap, null, destination, paint)
        }
    }
}

/**
 * Fills [surface] with opaque black through a throwaway EGL context and lets go of it again (#530),
 * on the calling thread, leaving nothing current there. Destroying the window surface disconnects it
 * from [surface], which a canvas post never does, so a MediaCodec decoder can still take [surface]
 * afterwards. False when EGL refuses any step, as it does while a decoder or a canvas holds the
 * Surface.
 */
internal fun blankThroughEgl(surface: Surface): Boolean {
    val display = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY)
    if (display == android.opengl.EGL14.EGL_NO_DISPLAY) return false
    val version = IntArray(2)
    if (!android.opengl.EGL14.eglInitialize(display, version, 0, version, 1)) return false
    var context = android.opengl.EGL14.EGL_NO_CONTEXT
    var window = android.opengl.EGL14.EGL_NO_SURFACE
    try {
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val found = IntArray(1)
        val wanted = intArrayOf(
            android.opengl.EGL14.EGL_RED_SIZE, 8,
            android.opengl.EGL14.EGL_GREEN_SIZE, 8,
            android.opengl.EGL14.EGL_BLUE_SIZE, 8,
            android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
            android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_WINDOW_BIT,
            android.opengl.EGL14.EGL_NONE,
        )
        if (!android.opengl.EGL14.eglChooseConfig(display, wanted, 0, configs, 0, 1, found, 0) || found[0] == 0) {
            return false
        }
        val config = configs[0] ?: return false
        context = android.opengl.EGL14.eglCreateContext(
            display,
            config,
            android.opengl.EGL14.EGL_NO_CONTEXT,
            intArrayOf(android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE),
            0,
        )
        if (context == android.opengl.EGL14.EGL_NO_CONTEXT) return false
        window = android.opengl.EGL14.eglCreateWindowSurface(
            display,
            config,
            surface,
            intArrayOf(android.opengl.EGL14.EGL_NONE),
            0,
        )
        if (window == android.opengl.EGL14.EGL_NO_SURFACE) return false
        if (!android.opengl.EGL14.eglMakeCurrent(display, window, window, context)) return false
        android.opengl.GLES20.glClearColor(0f, 0f, 0f, 1f)
        android.opengl.GLES20.glClear(android.opengl.GLES20.GL_COLOR_BUFFER_BIT)
        return android.opengl.EGL14.eglSwapBuffers(display, window)
    } finally {
        android.opengl.EGL14.eglMakeCurrent(
            display,
            android.opengl.EGL14.EGL_NO_SURFACE,
            android.opengl.EGL14.EGL_NO_SURFACE,
            android.opengl.EGL14.EGL_NO_CONTEXT,
        )
        if (window != android.opengl.EGL14.EGL_NO_SURFACE) android.opengl.EGL14.eglDestroySurface(display, window)
        if (context != android.opengl.EGL14.EGL_NO_CONTEXT) android.opengl.EGL14.eglDestroyContext(display, context)
        // Android counts initialisations, so this ends only this one and leaves other users' EGL alone.
        android.opengl.EGL14.eglTerminate(display)
        android.opengl.EGL14.eglReleaseThread()
    }
}
