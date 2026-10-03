package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.network.DownloadMeter
import io.github.yuroyami.kiteplayer.network.HttpReaderPolicy
import io.github.yuroyami.kiteplayer.network.KtorMediaIo
import io.github.yuroyami.kiteplayer.network.KtorMediaIoException
import io.github.yuroyami.kiteplayer.network.RedirectRule
import io.github.yuroyami.kiteplayer.network.originOf
import io.github.yuroyami.kiteplayer.network.shownUri
import io.github.yuroyami.kiteplayer.network.xml.XmlMini
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.ExperimentalTime

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
 * redirects it follows are the caller's to judge. [Dash.mediaItemFor] builds one that applies the
 * policy.
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
        val shown = shownUri(from.toString())
        val scheme = to.protocol.name
        if (scheme !in policy.allowedSchemes) {
            throw DashUrlRefusedException(
                "$shown redirects to scheme '$scheme', which is not in ${policy.allowedSchemes.sorted()}",
            )
        }
        if (!policy.allowSchemeDowngrade && manifest.protocol.name == "https" && scheme != "https") {
            throw DashUrlRefusedException(
                "$shown, from an https manifest, redirects over '$scheme'; set " +
                    "DashUrlPolicy(allowSchemeDowngrade = true) if that is genuinely intended",
            )
        }
        if (policy.sameOriginOnly && originOf(to) != originOf(manifest)) {
            throw DashUrlRefusedException(
                "$shown redirects to ${originOf(to)}, and this policy is sameOriginOnly for ${originOf(manifest)}",
            )
        }
    }

    override fun refusal(reason: String): Exception = DashUrlRefusedException(reason)

    override fun isRefusal(failure: Throwable): Boolean = failure is DashUrlRefusedException
}

/**
 * Where the readers of one DASH item get their bytes: the door's own client for
 * [Dash.mediaItemFor], or the reader the automatic transport found the manifest with (#400).
 */
internal class DashTransport(
    /** A reader of a URL the manifest names, with every redirect checked. */
    val open: suspend (url: String) -> MediaIo,
    /** At most `limit` bytes of a URL, refused typed past it; `what` names it in messages. */
    val fetch: suspend (url: String, limit: Long, what: String) -> ByteArray,
    /** The manifest fetched again from an address, the first one or the one its `Location` names, for a live presentation. */
    val refetch: suspend (url: String) -> DashManifest,
    /** The `Date` header of a URL's response, which a live clock reads (#404), or null when it sends none. */
    val date: suspend (url: String) -> String? = { null },
    /** How fast the network delivered the bytes of every reader [open] made, or null before it knows. */
    val bitsPerSecond: () -> Long?,
    /** Called once, when the item's reader closes. */
    val release: () -> Unit = {},
)

/** [inner], which also calls [release] once when it closes. */
private class ReleasingMediaIo(private val inner: MediaIo, private val release: () -> Unit) : MediaIo {
    private var released = false
    override val size: Long? get() = inner.size
    override val seekable: Boolean get() = inner.seekable
    override val location: String? get() = inner.location
    override val contentType: String? get() = inner.contentType
    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int = inner.read(into, offset, length)
    override suspend fun seek(position: Long) = inner.seek(position)
    override fun setWarningSink(sink: (PlaybackWarning) -> Unit) = inner.setWarningSink(sink)
    override suspend fun openRelated(uri: String): MediaIo? = inner.openRelated(uri)
    override fun networkBitsPerSecond(): Long? = inner.networkBitsPerSecond()

    override fun close() {
        if (released) return
        released = true
        try {
            inner.close()
        } finally {
            release()
        }
    }
}

/** Everything [io] reads, at most [limit] bytes of it, refused typed the moment it passes. */
internal suspend fun readAllBounded(io: MediaIo, limit: Long, what: String): ByteArray {
    io.size?.let { declared ->
        if (declared > limit) throw DashResponseTooLargeException("$what declares $declared bytes, and the ceiling is $limit")
    }
    val chunks = ArrayList<ByteArray>()
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = io.read(buffer, 0, buffer.size)
        if (read < 0) break
        if (read == 0) {
            delay(1)
            continue
        }
        total += read
        if (total > limit) throw DashResponseTooLargeException("$what passed the $limit byte ceiling while reading")
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
            parseManifest(body, mpdUrl, policy, maxManifestBytes)
        }

    /** [body] parsed as the manifest at [mpdUrl], under a length limit that never refuses what [maxManifestBytes] accepted. */
    private fun parseManifest(body: ByteArray, mpdUrl: String, policy: DashUrlPolicy, maxManifestBytes: Long): DashManifest {
        // UTF-8 never decodes to more UTF-16 code units than it had bytes, so a length limit
        // equal to the byte ceiling never refuses what the fetch accepted.
        val lengthLimit = maxManifestBytes.coerceIn(XmlMini.MAX_LENGTH.toLong(), Int.MAX_VALUE.toLong()).toInt()
        return DashManifestParser.parse(body.decodeToString(), mpdUrl, policy, XmlMini.Limits(maxLength = lengthLimit))
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
        val route = route(mpdUrl, manifest, policy, maxSegmentBytes)
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
        if (!DashDetection.declared(io.contentType, io.location)) {
            if (!DashDetection.worthSniffing(io.contentType)) return null
            val head = io.peek(DashDetection.SNIFF_BYTES)
            if (!DashDetection.startsLikeMpd(head, head.size)) return null
        }
        val mpdUrl = io.location
        val shown = shownUri(mpdUrl)
        DashManifestParser.requireAllowedScheme(mpdUrl, policy)
        val redirects = DashRedirectRule(policy, mpdUrl)
        suspend fun openChecked(url: String): KtorMediaIo =
            io.openRelated(url, redirects) ?: throw DashUrlRefusedException("${shownUri(url)} is not an http or https address")
        val manifest = parseManifest(readAllBounded(io, maxManifestBytes, "the manifest at $shown"), mpdUrl, policy, maxManifestBytes)
        val route = route(mpdUrl, manifest, policy, maxSegmentBytes)
        return route.reader(
            DashTransport(
                open = ::openChecked,
                fetch = { url, limit, what -> openChecked(url).use { readAllBounded(it, limit, what) } },
                refetch = { url ->
                    val body = openChecked(url).use { readAllBounded(it, maxManifestBytes, "the manifest at ${shownUri(url)}") }
                    parseManifest(body, url, policy, maxManifestBytes)
                },
                date = { url -> openChecked(url).use { it.date } },
                bitsPerSecond = io::networkBitsPerSecond,
                release = io::close,
            ),
        )
    }

    /**
     * How [manifest] plays: through the HLS path when HLS can carry its Periods, otherwise as one
     * stream. Every refusal happens here, before any reader exists.
     */
    private fun route(mpdUrl: String, manifest: DashManifest, policy: DashUrlPolicy, maxSegmentBytes: Long): DashRoute {
        val period = manifest.periods.firstOrNull()
            ?: throw IllegalArgumentException("${shownUri(mpdUrl)} has no Period")
        // Digital rights management is out of scope by decision (#404). An encrypted set beside
        // clear ones is left out; a Period with nothing clear to show is refused, not decoded to noise.
        for ((index, each) in manifest.periods.withIndex()) {
            val media = each.adaptationSets.filter { it.isVideo() || it.isAudio() }
            if (media.isNotEmpty() && media.all { it.isProtected }) {
                val schemes = media.flatMap { it.contentProtectionSchemes }.distinct().joinToString(", ")
                throw DashUnsupportedException(
                    "${shownUri(mpdUrl)} is encrypted" + (if (manifest.periods.size > 1) " in Period ${index + 1}" else "") +
                        " ($schemes), and digital rights management is not supported",
                )
            }
        }
        if (DashHls.carries(manifest)) {
            // Built here, so a manifest whose playlists cannot be written is refused before any open.
            // Every Period plays, joined onto the first one's tracks (#403).
            manifest.periodTimings()
            return DashRoute.Hls(mpdUrl, manifest, DashHls.presentation(period, live = manifest.isDynamic), policy)
        }
        // Refused, not truncated: the one-stream reader byte-concatenates ONE Period's segments,
        // and silently playing period one of an ad-stitched presentation looked like a player that
        // stops after the pre-roll.
        if (manifest.periods.size > 1) {
            throw DashUnsupportedException(
                "${shownUri(mpdUrl)} has ${manifest.periods.size} Periods in a container the HLS path does not take, " +
                    "and the one-stream reader plays exactly one",
            )
        }
        val video = period.adaptationSets.firstOrNull { it.isVideo() && !it.isProtected }
        // Merging two elementary streams is not a byte concatenation, so separate audio would be
        // lost. Refused typed rather than played silent.
        if (video != null && period.adaptationSets.any { it !== video && it.isAudio() && !it.isProtected }) {
            throw DashUnsupportedException(
                "${shownUri(mpdUrl)} carries its audio in a separate adaptation set, and this tier plays " +
                    "one set, so its video would play silent",
            )
        }
        val adaptationSet = video
            ?: period.adaptationSets.firstOrNull { !it.isProtected }
            ?: throw IllegalArgumentException("${shownUri(mpdUrl)} has no AdaptationSet")
        val representation = adaptationSet.representations.maxByOrNull { it.bandwidth }
            ?: throw IllegalArgumentException("${shownUri(mpdUrl)} has no Representation")
        if (manifest.isDynamic) {
            throw DashUnsupportedException("${shownUri(mpdUrl)} is live, and this tier plays on-demand presentations only")
        }
        if (representation.segmentTemplate == null && representation.segmentUrls.isEmpty()) {
            // One file at the representation's base URL. The base is the manifest itself when no
            // BaseURL names the media, and then there is nothing to play.
            val file = representation.baseUrl
            require(file != mpdUrl) { "${shownUri(mpdUrl)} names no media for representation ${representation.id}" }
            return DashRoute.File(file)
        }
        // The plan itself is immutable and shared by every reader the route makes.
        return DashRoute.Segments(DashManifestParser.segmentPlan(manifest, period, representation, policy), maxSegmentBytes)
    }

    /** One way a manifest plays, decided once; [reader] makes the reader of one open over a [DashTransport]. */
    private sealed interface DashRoute {
        suspend fun reader(transport: DashTransport): MediaIo

        /** Through the HLS path: the reader stands in for an HLS master playlist. */
        class Hls(
            private val mpdUrl: String,
            private val manifest: DashManifest,
            private val presentation: DashHlsPresentation,
            private val policy: DashUrlPolicy,
        ) : DashRoute {
            override suspend fun reader(transport: DashTransport): MediaIo = DashHlsMediaIo(
                presentation = presentation,
                manifest = manifest,
                manifestUrl = mpdUrl,
                policy = policy,
                openUrl = transport.open,
                refetch = if (manifest.isDynamic) transport.refetch else null,
                nowMicros = ::wallClockMicros,
                fetchDate = transport.date,
                bitsPerSecond = transport.bitsPerSecond,
                release = transport.release,
            )
        }

        /** One file, read with range requests, so it seeks. */
        class File(private val url: String) : DashRoute {
            override suspend fun reader(transport: DashTransport): MediaIo =
                ReleasingMediaIo(transport.open(url), transport.release)
        }

        /** One representation's segments as one forward stream. */
        class Segments(private val plan: DashSegmentPlan, private val maxSegmentBytes: Long) : DashRoute {
            override suspend fun reader(transport: DashTransport): MediaIo = ReleasingMediaIo(
                DashMediaIo(plan) { url -> transport.fetch(url, maxSegmentBytes, "the segment at ${shownUri(url)}") },
                transport.release,
            )
        }
    }

    /** The time of day, in microseconds since 1970 UTC, which a live manifest's clock counts from. */
    @OptIn(ExperimentalTime::class)
    private fun wallClockMicros(): Long {
        val now = Clock.System.now()
        return now.epochSeconds * 1_000_000 + now.nanosecondsOfSecond / 1_000
    }

    private fun DashAdaptationSet.isVideo(): Boolean =
        contentType == "video" || mimeType?.startsWith("video/") == true ||
            representations.any { it.mimeType?.startsWith("video/") == true }

    private fun DashAdaptationSet.isAudio(): Boolean =
        contentType == "audio" || mimeType?.startsWith("audio/") == true ||
            representations.any { it.mimeType?.startsWith("audio/") == true }
}
