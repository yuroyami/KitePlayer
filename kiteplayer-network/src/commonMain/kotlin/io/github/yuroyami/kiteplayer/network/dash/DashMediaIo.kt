package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
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
public object Dash {

    /**
     * Fetches and parses [mpdUrl]. Confined to [Dispatchers.Default] for the same reason
     * [DashMediaIo] confines its fetches: on Kotlin/Native the Darwin engine resumes onto the
     * main queue, which a plain runBlocking main thread never serves, and the fetch deadlocks.
     */
    public suspend fun manifest(mpdUrl: String, client: HttpClient): DashManifest =
        withContext(Dispatchers.Default) {
            val response = client.get(mpdUrl)
            require(response.status.isSuccess()) { "cannot fetch $mpdUrl: ${response.status}" }
            DashManifestParser.parse(response.bodyAsText(), mpdUrl)
        }

    /**
     * A playable [MediaItem] for [mpdUrl]: the chosen representation's segments as one
     * [DashMediaIo] stream over [client]. The item's uri stays the manifest's, for labels.
     */
    public suspend fun mediaItemFor(mpdUrl: String, client: HttpClient): MediaItem {
        val manifest = manifest(mpdUrl, client)
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
        val plan = DashManifestParser.segmentPlan(manifest, period, representation)
        val io = DashMediaIo(plan) { url ->
            val response = client.get(url)
            require(response.status.isSuccess()) { "segment fetch failed: $url is ${response.status}" }
            response.bodyAsBytes()
        }
        return MediaItem(uri = mpdUrl, io = io)
    }

    private fun DashAdaptationSet.isVideo(): Boolean =
        contentType == "video" || mimeType?.startsWith("video/") == true ||
            representations.any { it.mimeType?.startsWith("video/") == true }
}
