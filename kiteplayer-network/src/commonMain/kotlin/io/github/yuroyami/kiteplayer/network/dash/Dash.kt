package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.network.DownloadMeter
import io.github.yuroyami.kiteplayer.network.HttpReaderPolicy
import io.github.yuroyami.kiteplayer.network.KtorMediaIo
import io.github.yuroyami.kiteplayer.network.KtorMediaIoException
import io.github.yuroyami.kiteplayer.network.RedirectRule
import io.github.yuroyami.kiteplayer.network.originOf
import io.github.yuroyami.kiteplayer.network.shownUri
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * At most [limit] bytes of [response], refused typed the moment it passes.
 *
 * `bodyAsBytes()` and `bodyAsText()` buffer whatever the server sends, with no ceiling at all, so
 * a hostile or broken endpoint could take the process out with a response nobody asked to be that
 * big. The declared Content-Length is checked first because it costs nothing, and then the read
 * itself is bounded, because a server is free to declare one length and send another.
 */
private suspend fun readBounded(response: HttpResponse, limit: Long, what: String, readTimeout: Duration): ByteArray {
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
        val read = withTimeoutOrNull(readTimeout) { channel.readAvailable(buffer, 0, buffer.size) }
            ?: throw KtorMediaIoException("$what sent no bytes for $readTimeout")
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

/**
 * One GET of [url], limited by [reader]: the response headers must arrive within its connect
 * timeout and each later chunk within its read timeout. The door applies them itself, because the
 * browser engine has no limit of its own and a silent server held the call for ever (#242). The
 * connect timeout covers every redirect of the request, and [redirects] checks each one.
 */
private suspend fun fetchBounded(
    client: HttpClient,
    url: String,
    limit: Long,
    what: String,
    reader: HttpReaderPolicy,
    redirects: RedirectRule,
    refusal: (HttpStatusCode) -> String,
): ByteArray = coroutineScope {
    val answered = CompletableDeferred<Unit>()
    val body = async {
        try {
            redirects.guard(client) { mark ->
                client.prepareGet(url) { mark() }.execute { response ->
                    answered.complete(Unit)
                    redirects.refuseHidden(response, shownUri(url))
                    require(response.status.isSuccess()) { refusal(response.status) }
                    readBounded(response, limit, what, reader.readTimeout)
                }
            }
        } catch (failure: Throwable) {
            answered.completeExceptionally(failure)
            throw failure
        }
    }
    if (withTimeoutOrNull(reader.connectTimeout) { answered.await() } == null) {
        body.cancel()
        throw KtorMediaIoException("no answer from ${shownUri(url)} within ${reader.connectTimeout}")
    }
    body.await()
}

/**
 * [policy] applied to every redirect of a request that the DASH door makes for the manifest at
 * [manifestUrl]: its scheme, its downgrade rule and, for [DashUrlPolicy.sameOriginOnly], the
 * scheme, host and port of [manifestUrl] itself.
 */
internal class DashRedirectRule(private val policy: DashUrlPolicy, manifestUrl: String) :
    RedirectRule(refusesHiddenRedirects = policy.sameOriginOnly) {

    private val manifest = Url(manifestUrl)

    override fun check(from: Url, to: Url) {
        DashDoor.requireRedirectAllowed(
            policy = policy,
            shown = shownUri(from.toString()),
            manifestScheme = manifest.protocol.name,
            manifestOrigin = originOf(manifest),
            toScheme = to.protocol.name,
            toOrigin = originOf(to),
        )
    }

    override fun refusal(reason: String): Exception = DashUrlRefusedException(reason)

    override fun isRefusal(failure: Throwable): Boolean = failure is DashUrlRefusedException
}

/** Opens DASH presentations: fetches a manifest, and builds a playable item from it. */
public object Dash {

    /**
     * Fetches and parses [mpdUrl]. Confined to [Dispatchers.Default] for the same reason
     * [DashMediaIo] confines its fetches: on Kotlin/Native the Darwin engine resumes onto the
     * main queue, which a plain runBlocking main thread never serves, and the fetch deadlocks.
     *
     * [readerPolicy] limits the wait: a server that sends no headers within its connect timeout,
     * or no bytes within its read timeout, fails the call with [KtorMediaIoException].
     *
     * [policy] judges the manifest's own redirects too, against [mpdUrl], and refuses one with
     * [DashUrlRefusedException] before it is requested. A request follows at most five redirects.
     *
     * A manifest within [maxManifestBytes] is never refused for its length. One with more than
     * 262,144 elements or 1,048,576 attributes, or with an element that carries more than 128
     * attributes, is refused with [io.github.yuroyami.kiteplayer.network.xml.XmlException] whatever
     * the ceiling. The URL ceilings that [DashManifestParser.parse] states grow in proportion once
     * [maxManifestBytes] passes 8 MiB.
     */
    @Throws(Exception::class)
    public suspend fun manifest(
        mpdUrl: String,
        client: HttpClient,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
        maxManifestBytes: Long = MAX_MANIFEST_BYTES,
        readerPolicy: HttpReaderPolicy = HttpReaderPolicy(),
    ): DashManifest =
        withContext(Dispatchers.Default) {
            // Checked BEFORE the fetch, not after: the point of the policy is that a URL this
            // player will not accept is also a URL it never sends the caller's cookies to.
            DashManifestParser.requireAllowedScheme(mpdUrl, policy)
            val shown = shownUri(mpdUrl)
            val redirects = DashRedirectRule(policy, mpdUrl)
            val body = fetchBounded(client, mpdUrl, maxManifestBytes, "the manifest at $shown", readerPolicy, redirects) { status ->
                "cannot fetch $shown: $status"
            }
            DashDoor.manifest(body, mpdUrl, policy, maxManifestBytes)
        }

    /**
     * A playable [MediaItem] for [mpdUrl], over [client]. The item's uri stays the manifest's, for
     * labels. [readerPolicy] limits the manifest fetch and every later fetch, as in [manifest].
     *
     * When every picture and sound representation of the Period is fragmented MP4, MPEG-TS or WebM,
     * the item plays through the player's HLS path (#295, #401). The door writes an HLS master
     * playlist with a variant for each video representation, an audio rendition for each audio set
     * and a subtitle rendition for each subtitle set, and a media playlist for each, from the
     * manifest's templates, timelines, lists or segment indexes: an MP4 file's `sidx` or a WebM
     * file's `Cues`. So separate audio and video sets play together, the item seeks, and its
     * variants are listed and chosen as an HLS item's are. Its segment readers share one measure of
     * the network rate, which the automatic variant steps read. A subtitle set of WebVTT plays as it
     * is, and one of TTML, or of TTML or WebVTT in MP4 segments (`stpp`, `wvtt`), is served as
     * WebVTT, the only form FFmpeg's HLS reader takes (#402). A live (dynamic) manifest plays live:
     * its playlists follow the time of day and the manifest is fetched again after each minimum
     * update period.
     *
     * A live manifest counts its window on the time of day its first usable `UTCTiming` names
     * (`direct`, `http-xsdate`, `http-iso` or `http-head`), or else on the device's clock, and each
     * refresh fetches it from the address its `Location` names (#404). Each audio and subtitle
     * rendition is named by its set's `Label`, and its DASH roles say whether it is the main
     * sound, forced subtitles, captions or a description of the picture. A set with a
     * `ContentProtection` element is left out, and a Period with nothing else to show is refused.
     *
     * A manifest of several Periods plays as one presentation (#403), as ad insertion and chapters
     * stitch them: the tracks are the first Period's, each later Period gives each track the set
     * with the same `id`, or at the same place, or in the same language, and the representation
     * nearest its bandwidth. Every segment is moved onto one timeline, so time runs on across each
     * boundary, and a live manifest that a refresh gives another Period plays on into it. Fragmented
     * MP4 is written again for the initialization its stream began with, and H.264 and HEVC carry
     * their own Period's parameter sets in band, so a Period of another picture size decodes at its
     * own. An MP4 Period in another codec than the stream began with is skipped, and a Period
     * without a set for a track leaves that track a gap there.
     *
     * Otherwise the door plays one representation, as one stream: the highest bandwidth one of
     * the first video adaptation set, or of the first set when there is no video. Its segments
     * play as one [DashMediaIo] stream, which cannot seek. A representation with no segment
     * addressing is one file, and it is read through [KtorMediaIo] with range requests, so it is
     * seekable and never held in memory whole. [maxSegmentBytes] limits the segments of that
     * stream; the HLS path reads segments as streams and holds none whole.
     *
     * [policy] judges every URL the item asks for and every redirect of every request, as in
     * [manifest]. A segment or file behind a refused redirect fails its read with
     * [DashUrlRefusedException].
     *
     * Throws [DashUnsupportedException] for an encrypted manifest, and, when the HLS path cannot
     * carry the segments, for more than one Period, because the one stream would stop after the
     * first, for a live manifest, and for audio in an adaptation set of its own, because the one
     * stream would play that video silent.
     *
     * The player's automatic transport plays a manifest it recognises the same way, with no call
     * to this (#400). This door is for a caller with a client of its own, or a policy other than
     * [DashUrlPolicy.Default].
     */
    @Throws(Exception::class)
    public suspend fun mediaItemFor(
        mpdUrl: String,
        client: HttpClient,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
        maxManifestBytes: Long = MAX_MANIFEST_BYTES,
        maxSegmentBytes: Long = MAX_SEGMENT_BYTES,
        readerPolicy: HttpReaderPolicy = HttpReaderPolicy(),
    ): MediaItem {
        val manifest = manifest(mpdUrl, client, policy, maxManifestBytes, readerPolicy)
        val redirects = DashRedirectRule(policy, mpdUrl)
        val route = DashDoor.route(mpdUrl, manifest, policy, maxSegmentBytes)
        // A factory, so every open of this item gets its own reader. One live reader here meant
        // the second open of the same item -- a track switch, a loop, a queue coming back round --
        // was handed the one the previous session had already closed. The route is immutable and
        // shared by every reader the factory makes.
        return MediaItem(
            uri = mpdUrl,
            io = {
                // One measure of the network rate per open, shared by all its segment readers.
                val meter = DownloadMeter()
                route.reader(
                    DashTransport(
                        open = { url -> KtorMediaIo.open(url, client, emptyMap(), readerPolicy, redirects, meter = meter) },
                        fetch = { url, limit, what ->
                            fetchBounded(client, url, limit, what, readerPolicy, redirects) { status ->
                                "segment fetch failed: ${shownUri(url)} is $status"
                            }
                        },
                        refetch = { url -> manifest(url, client, policy, maxManifestBytes, readerPolicy) },
                        date = { url ->
                            KtorMediaIo.open(url, client, emptyMap(), readerPolicy, redirects, meter = meter).use { it.date }
                        },
                        bitsPerSecond = meter::bitsPerSecond,
                    ),
                )
            },
        )
    }

    /**
     * The reader that plays the manifest [io] has just begun to answer with, or null when the
     * answer is not a manifest (#400). A manifest is recognised by its content type, by a path that
     * ends in `.mpd`, or, when the type leaves room for one, by its root element, and [io] then
     * keeps the bytes it read for the backend.
     *
     * The manifest plays as [mediaItemFor] plays it, under [policy], with its relative addresses
     * resolved against the address the manifest came from after its redirects. Its segments and
     * its refetches open through [io], so they share its client, its network measure and the
     * item's headers on the manifest's own origin, and the returned reader closes [io] with it.
     */
    internal suspend fun readerIfManifest(
        io: KtorMediaIo,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
        maxManifestBytes: Long = MAX_MANIFEST_BYTES,
        maxSegmentBytes: Long = MAX_SEGMENT_BYTES,
    ): MediaIo? {
        // Made on the first open, so an address the policy refuses is refused before it is parsed.
        val redirects by lazy { DashRedirectRule(policy, io.location) }
        return DashDoor.readerIfManifest(
            io = io,
            peek = io::peek,
            open = { url -> io.openRelated(url, redirects) },
            dateOf = { (it as KtorMediaIo).date },
            policy = policy,
            maxManifestBytes = maxManifestBytes,
            maxSegmentBytes = maxSegmentBytes,
            // The manifest names its segments itself, so the reader need not find them in a playlist (#547).
            openSegment = if (io.keepsSegments) ({ url -> io.openRelated(url, redirects, segment = true) }) else null,
        )
    }
}
