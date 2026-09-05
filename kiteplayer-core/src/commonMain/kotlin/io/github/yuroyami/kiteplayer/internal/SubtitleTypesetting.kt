package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.VideoScale
import io.github.yuroyami.kiteplayer.spi.OverlayImage
import io.github.yuroyami.kiteplayer.spi.SubtitleTypesetter
import io.github.yuroyami.kiteplayer.spi.TypesetFrame
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.subtitle.SubtitleStyleOverride
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.Job

/** One mutation of the typesetter, staged on the actor and applied on the raster lane. */
internal sealed interface TypesetOp {
    class Header(val bytes: ByteArray) : TypesetOp
    class Document(val bytes: ByteArray) : TypesetOp
    class Font(val name: String, val data: ByteArray) : TypesetOp
    class Event(val payload: ByteArray, val startMillis: Long, val durationMillis: Long) : TypesetOp
    data object Clear : TypesetOp
}

/**
 * What one render should draw: the position, the geometry, and the cues the typesetter does NOT
 * own (the secondary lane, bitmap cues), which the platform rasterizer still draws beside it.
 */
internal class TypesetRequest(
    val timeMillis: Long,
    val frame: TypesetFrame,
    val otherCues: List<SubtitleCue>,
    /** The viewer's override for [otherCues]; the typeset track keeps its authored look. */
    val otherStyle: SubtitleStyleOverride?,
    /** The lane epoch this request belongs to; a withdrawal bumps it and orphans the request. */
    val epoch: Long,
)

/**
 * The typesetting half of one session: the engine, its staged mutations and the render job.
 *
 * Two threads touch it and they never share a field without a fence. The actor stages mutations
 * and posts requests; the raster lane applies the mutations, renders and publishes. Requests
 * COALESCE: the newest replaces an unrendered older one, so a slow render never builds a queue,
 * and the loop protocol in [PlaybackCore] guarantees a request posted while the job is winding
 * down is still rendered rather than lost.
 */
internal class TypesetLane(
    /** Which track this lane draws, `stream:<index>` or `external:<id>`, so a refresh can tell "same". */
    val key: String,
    val providerId: String,
    private val typesetter: SubtitleTypesetter,
) {
    private val lock = SynchronizedObject()
    private var staged = ArrayList<TypesetOp>()

    /** The newest unrendered request, or null. Set by the actor, taken by the raster lane. */
    val requested = atomic<TypesetRequest?>(null)

    /** True while the render job owns the lane. The CAS on it is the whole handover protocol. */
    val running = atomic(false)

    /** Bumped by the actor on withdrawal and close; a request from an older epoch never publishes. */
    val epoch = atomic(0L)

    /** Set by the raster lane when the engine threw; the actor then falls back to the Kotlin tier. */
    val failed = atomic<Throwable?>(null)

    /** The images of the last render that changed something, kept so unchanged frames republish them. */
    var lastImages: List<OverlayImage> = emptyList()

    /** Raster lane only: the other cues drawn last time, so a changed secondary lane republishes alone. */
    var publishedOthers: List<SubtitleCue>? = null

    /** Actor only: what the last request asked for, to skip a pass that would ask the same. */
    var lastRequestMillis: Long = Long.MIN_VALUE
    var lastRequestFrame: TypesetFrame? = null
    var lastRequestOthers: List<SubtitleCue>? = null

    /** The render job, actor-owned like every other session job. */
    var job: Job? = null

    /** True once this lane has put images on the glass, so a withdrawal knows to clear them. */
    val published = atomic(false)

    fun stage(op: TypesetOp) {
        synchronized(lock) { staged += op }
    }

    fun hasStaged(): Boolean = synchronized(lock) { staged.isNotEmpty() }

    /**
     * Raster lane only. Applies everything staged, then renders. Null means the picture is what
     * it was; a list is what the glass should show now.
     */
    fun renderNow(request: TypesetRequest): List<OverlayImage>? {
        val ops = synchronized(lock) {
            val taken = staged
            staged = ArrayList()
            taken
        }
        ops.forEach { op ->
            when (op) {
                is TypesetOp.Header -> typesetter.openTrack(op.bytes)
                is TypesetOp.Document -> typesetter.openDocument(op.bytes)
                is TypesetOp.Font -> typesetter.addFont(op.name, op.data)
                is TypesetOp.Event -> typesetter.addEvent(op.payload, op.startMillis, op.durationMillis)
                TypesetOp.Clear -> typesetter.clearEvents()
            }
        }
        return typesetter.render(request.timeMillis, request.frame)
    }

    /** Raster lane only, after the job has ended. */
    fun close() {
        typesetter.close()
    }
}

/** Whether a container codec name is one the typesetter owns. */
internal fun isTypesetCodec(codec: String?): Boolean =
    codec.equals("ass", ignoreCase = true) || codec.equals("ssa", ignoreCase = true)

/**
 * The bars around a picture fitted into a surface, as (top, bottom, left, right) margins.
 *
 * Only [VideoScale.Fit] leaves bars. Fill and Stretch cover the surface, so the script is laid
 * out over the whole of it, which is also what the Kotlin tier does for those modes.
 */
internal fun fittedMargins(
    surfaceWidth: Int,
    surfaceHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
    scale: VideoScale,
): IntArray {
    if (scale != VideoScale.Fit || videoWidth <= 0 || videoHeight <= 0) return IntArray(4)
    // In Long: a 4K frame against a 4K surface multiplies past Int on the cross terms.
    val surfaceWide = surfaceWidth.toLong() * videoHeight.toLong() > videoWidth.toLong() * surfaceHeight.toLong()
    return if (surfaceWide) {
        // Pillarbox: the picture is height-bound and bars sit left and right.
        val fittedWidth = (videoWidth.toLong() * surfaceHeight.toLong() / videoHeight.toLong()).toInt()
        val bar = (surfaceWidth - fittedWidth).coerceAtLeast(0)
        intArrayOf(0, 0, bar / 2, bar - bar / 2)
    } else {
        // Letterbox: the picture is width-bound and bars sit top and bottom.
        val fittedHeight = (videoHeight.toLong() * surfaceWidth.toLong() / videoWidth.toLong()).toInt()
        val bar = (surfaceHeight - fittedHeight).coerceAtLeast(0)
        intArrayOf(bar / 2, bar - bar / 2, 0, 0)
    }
}
