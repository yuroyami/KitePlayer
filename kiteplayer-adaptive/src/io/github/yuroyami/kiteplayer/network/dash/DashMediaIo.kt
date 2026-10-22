package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel

/**
 * One representation's segments read as a single forward stream (the adaptive
 * layer's first tier): the initialization segment when one exists, then every media segment
 * in plan order, byte-concatenated. FFmpeg demuxes the join exactly as it demuxes a file,
 * which is the media3 shape chosen here: Kotlin segment logic FEEDING the
 * decoder, never FFmpeg's own dash demuxer.
 *
 * Forward-only and unsized on purpose: a segment plan's total byte size is unknown until the
 * last fetch, and lying about seekability would let the demuxer walk into a wall. Seeking a
 * DASH presentation properly means segment arithmetic at the PLAYER level; this tier plays.
 *
 * [fetch] is the caller's own, so [DashUrlPolicy] does not reach it: what it requests and which
 * redirects it follows are the caller's to judge. `Dash.mediaItemFor` of `kiteplayer-network`
 * builds one that applies the policy.
 */
public class DashMediaIo(
    plan: DashSegmentPlan,
    private val fetch: suspend (String) -> ByteArray,
) : MediaIo {

    private val urls: List<String> = listOfNotNull(plan.initializationUrl) + plan.mediaUrls
    private var urlIndex = 0
    private var segment: ByteArray? = null
    private var segmentAt = 0

    /**
     * Fetches run on this owned scope and are awaited, never called inline: the demux worker
     * reaches read() through a nested runBlocking whose event loop must stay the resumption
     * target, and an http engine that hops dispatchers mid-request deadlocks the inline form
     * on Kotlin/Native. The Ktor reader's pipe design dodges the same trap the same way.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val size: Long? = null
    override val seekable: Boolean = false

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        while (true) {
            val current = segment
            if (current != null && segmentAt < current.size) {
                val count = (current.size - segmentAt).coerceAtMost(length)
                current.copyInto(into, offset, segmentAt, segmentAt + count)
                segmentAt += count
                return count
            }
            if (urlIndex >= urls.size) return -1
            val url = urls[urlIndex]
            segment = scope.async { fetch(url) }.await()
            segmentAt = 0
            urlIndex++
        }
    }

    override suspend fun seek(position: Long) {
        throw UnsupportedOperationException("a DASH segment stream is forward-only at this tier")
    }

    override fun close() {
        scope.cancel()
        segment = null
        urlIndex = urls.size
    }
}

/**
 * A manifest that the DASH door does not play: an encrypted one, whose every picture and sound set
 * has a `ContentProtection` element, because digital rights management is out of scope, or one
 * whose segments the HLS path cannot carry, in a container other than fragmented MP4, MPEG-TS or
 * WebM, when it has several Periods, is live or carries its audio in an adaptation set of its own.
 * It is refused rather than played wrong, so an application can fall back to another route.
 */
public class DashUnsupportedException(message: String) : IllegalArgumentException(message)

/** A response that passed the size ceiling before it was fully read. */
public class DashResponseTooLargeException(message: String) : IllegalStateException(message)

/** Default ceiling for one MPD. Real manifests are kilobytes; this is three orders above them. */
public const val MAX_MANIFEST_BYTES: Long = 8L shl 20

/** Default ceiling for one media segment. A 10 second 4K segment is well inside this. */
public const val MAX_SEGMENT_BYTES: Long = 64L shl 20
