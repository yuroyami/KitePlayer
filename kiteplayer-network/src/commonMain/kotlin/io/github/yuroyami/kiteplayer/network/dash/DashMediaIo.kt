package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * One representation's segments read as a single forward stream (KPKMP 17.12, the adaptive
 * layer's first tier): the initialization segment when one exists, then every media segment
 * in plan order, byte-concatenated. FFmpeg demuxes the join exactly as it demuxes a file,
 * which is the media3 shape the un-parked D-4 chose: Kotlin segment logic FEEDING the
 * decoder, never FFmpeg's own dash demuxer.
 *
 * Forward-only and unsized on purpose: a segment plan's total byte size is unknown until the
 * last fetch, and lying about seekability would let the demuxer walk into a wall. Seeking a
 * DASH presentation properly means segment arithmetic at the PLAYER level; this tier plays.
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
 * The one-call DASH door: fetch, parse, pick, play.
 *
 * Representation choice at this tier: the first period's video adaptation set when one
 * exists (audio-only manifests fall back to audio), and the HIGHEST bandwidth representation
 * in it. Stated honestly: a presentation with SEPARATE audio and video adaptation sets plays
 * its video muted, because merging two elementary segment streams is the adaptive engine's
 * next tier, not a byte concatenation.
 */
/** A response that passed the size ceiling before it was fully read. */
public class DashResponseTooLargeException(message: String) : IllegalStateException(message)

/** Default ceiling for one MPD. Real manifests are kilobytes; this is three orders above them. */
public const val MAX_MANIFEST_BYTES: Long = 8L shl 20

/** Default ceiling for one media segment. A 10 second 4K segment is well inside this. */
public const val MAX_SEGMENT_BYTES: Long = 64L shl 20

/**
 * At most [limit] bytes of [response], refused typed the moment it passes (SEC-6).
 *
 * `bodyAsBytes()` and `bodyAsText()` buffer whatever the server sends, with no ceiling at all, so
 * a hostile or broken endpoint could take the process out with a response nobody asked to be that
 * big. The declared Content-Length is checked first because it costs nothing, and then the read
 * itself is bounded, because a server is free to declare one length and send another.
 */
private suspend fun readBounded(response: HttpResponse, limit: Long, what: String): ByteArray {
    response.contentLength()?.let { declared ->
        if (declared > limit) {
            throw DashResponseTooLargeException(
                "$what declares $declared bytes, and the ceiling is $limit",
            )
        }
    }
    val channel: ByteReadChannel = response.bodyAsChannel()
    val chunks = ArrayList<ByteArray>()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = channel.readAvailable(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) continue
        total += read
        if (total > limit) {
            throw DashResponseTooLargeException("$what passed the $limit byte ceiling while reading")
        }
        chunks += buffer.copyOf(read)
    }
    val out = ByteArray(total.toInt())
    var at = 0
    for (chunk in chunks) {
        chunk.copyInto(out, at)
        at += chunk.size
    }
    return out
}

public object Dash {

    /**
     * Fetches and parses [mpdUrl]. Confined to [Dispatchers.Default] for the same reason
     * [DashMediaIo] confines its fetches: on Kotlin/Native the Darwin engine resumes onto the
     * main queue, which a plain runBlocking main thread never serves, and the fetch deadlocks.
     */
    public suspend fun manifest(
        mpdUrl: String,
        client: HttpClient,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
        maxManifestBytes: Long = MAX_MANIFEST_BYTES,
    ): DashManifest =
        withContext(Dispatchers.Default) {
            // Checked BEFORE the fetch, not after: the point of the policy is that a URL this
            // player will not accept is also a URL it never sends the caller's cookies to (SEC-2).
            DashManifestParser.requireAllowedScheme(mpdUrl, policy)
            val response = client.get(mpdUrl)
            require(response.status.isSuccess()) { "cannot fetch $mpdUrl: ${response.status}" }
            val body = readBounded(response, maxManifestBytes, "the manifest at $mpdUrl")
            DashManifestParser.parse(body.decodeToString(), mpdUrl, policy)
        }

    /**
     * A playable [MediaItem] for [mpdUrl]: the chosen representation's segments as one
     * [DashMediaIo] stream over [client]. The item's uri stays the manifest's, for labels.
     */
    public suspend fun mediaItemFor(
        mpdUrl: String,
        client: HttpClient,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
        maxManifestBytes: Long = MAX_MANIFEST_BYTES,
        maxSegmentBytes: Long = MAX_SEGMENT_BYTES,
    ): MediaItem {
        val manifest = manifest(mpdUrl, client, policy, maxManifestBytes)
        // Refused, not truncated (audit F-DASH3): this tier byte-concatenates ONE period's
        // segments, and silently playing period one of an ad-stitched presentation looked like
        // a player that stops after the pre-roll. Period joining is the adaptive engine's next
        // tier; until it exists the refusal is typed.
        require(manifest.periods.size <= 1) {
            "$mpdUrl has ${manifest.periods.size} Periods, and this tier plays exactly one; " +
                "multi-period joining is not implemented yet"
        }
        val period = manifest.periods.firstOrNull()
            ?: throw IllegalArgumentException("$mpdUrl has no Period")
        val adaptationSet = period.adaptationSets.firstOrNull { it.isVideo() }
            ?: period.adaptationSets.firstOrNull()
            ?: throw IllegalArgumentException("$mpdUrl has no AdaptationSet")
        val representation = adaptationSet.representations.maxByOrNull { it.bandwidth }
            ?: throw IllegalArgumentException("$mpdUrl has no Representation")
        val plan = DashManifestParser.segmentPlan(manifest, period, representation, policy)
        // A factory, so every open of this item gets its own segment stream. One live reader here
        // meant the second open of the same item -- a track switch, a loop, a queue coming back
        // round -- was handed the one the previous session had already closed (audit KP-P1-03).
        // The plan itself is immutable and shared by every reader the factory makes.
        return MediaItem(
            uri = mpdUrl,
            io = {
                DashMediaIo(plan) { url ->
                    val response = client.get(url)
                    require(response.status.isSuccess()) { "segment fetch failed: $url is ${response.status}" }
                    readBounded(response, maxSegmentBytes, "the segment at $url")
                }
            },
        )
    }

    private fun DashAdaptationSet.isVideo(): Boolean =
        contentType == "video" || mimeType?.startsWith("video/") == true ||
            representations.any { it.mimeType?.startsWith("video/") == true }
}
