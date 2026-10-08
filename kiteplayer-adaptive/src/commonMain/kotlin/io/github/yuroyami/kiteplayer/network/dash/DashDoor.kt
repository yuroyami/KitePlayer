package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.SourceRefusal
import io.github.yuroyami.kiteplayer.network.xml.XmlLimits
import io.github.yuroyami.kiteplayer.network.xml.XmlMini
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Where the readers of one DASH item get their bytes: the client of `Dash.mediaItemFor` in
 * `kiteplayer-network`, or the reader a transport found the manifest with (#400).
 */
@KitePlayerInternalApi
public class DashTransport(
    /** A reader of a URL the manifest names, with every redirect checked. */
    public val open: suspend (url: String) -> MediaIo,
    /** At most `limit` bytes of a URL, refused typed past it; `what` names it in messages. */
    public val fetch: suspend (url: String, limit: Long, what: String) -> ByteArray,
    /** The manifest fetched again from an address, the first one or the one its `Location` names, for a live presentation. */
    public val refetch: suspend (url: String) -> DashManifest,
    /** The `Date` header of a URL's response, which a live clock reads (#404), or null when it sends none. */
    public val date: suspend (url: String) -> String? = { null },
    /** How fast the network delivered the bytes of every reader [open] made, or null before it knows. */
    public val bitsPerSecond: () -> Long?,
    /** Called once, when the item's reader closes. */
    public val release: () -> Unit = {},
    /** The newest refusal of a request any reader [open] made, once (#453). */
    public val refusal: () -> SourceRefusal? = { null },
)

/**
 * [this] with every open and every fetch going through [failover] (#440), so a segment whose
 * location fails is read from another the manifest names. A moved address is judged by [policy]
 * as any other the manifest names.
 */
internal fun DashTransport.failingOver(failover: DashFailover, mpdUrl: String, policy: DashUrlPolicy): DashTransport {
    val inner = this
    fun checked(url: String, target: String): String =
        if (target == url) url else DashManifestParser.requireAllowed(mpdUrl, target, policy)
    return DashTransport(
        open = { url -> failover.open(url) { target -> inner.open(checked(url, target)) } },
        fetch = { url, limit, what -> failover.open(url) { target -> inner.fetch(checked(url, target), limit, what) } },
        refetch = inner.refetch,
        date = inner.date,
        bitsPerSecond = inner.bitsPerSecond,
        release = inner.release,
        refusal = inner.refusal,
    )
}

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
    override fun takeRefusal(): SourceRefusal? = inner.takeRefusal()
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
@KitePlayerInternalApi
public suspend fun readAllBounded(io: MediaIo, limit: Long, what: String): ByteArray {
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

/**
 * The DASH door without a transport: it recognises a manifest, decides how the manifest plays and
 * builds the reader, and a [DashTransport] makes every request. `kiteplayer-network` gives it Ktor's
 * requests, and the player in a Web Worker gives it synchronous ones (#546).
 */
@KitePlayerInternalApi
public object DashDoor {

    /** [body] parsed as the manifest at [mpdUrl], under a length limit that never refuses what [maxManifestBytes] accepted. */
    public fun manifest(body: ByteArray, mpdUrl: String, policy: DashUrlPolicy, maxManifestBytes: Long): DashManifest {
        // UTF-8 never decodes to more UTF-16 code units than it had bytes, so a length limit
        // equal to the byte ceiling never refuses what the fetch accepted.
        val lengthLimit = maxManifestBytes.coerceIn(XmlMini.MAX_LENGTH.toLong(), Int.MAX_VALUE.toLong()).toInt()
        return DashManifestParser.parse(body.decodeToString(), mpdUrl, policy, XmlLimits(maxLength = lengthLimit))
    }

    /**
     * The reader that plays the manifest [io] has just begun to answer with, or null when the
     * answer is not a manifest (#400). A manifest is recognised by its content type, by a path that
     * ends in `.mpd`, or, when the type leaves room for one, by its root element, which `peek`
     * reads and [io] then keeps for the backend.
     *
     * The manifest plays under [policy], with its relative addresses resolved against the address
     * the manifest came from after its redirects. Its segments and its refetches open through
     * [open], which answers null for an address it does not take and judges every redirect itself.
     * [dateOf] reads the `Date` header of a reader that [open] made. The returned reader closes
     * [io] with it.
     */
    public suspend fun readerIfManifest(
        io: MediaIo,
        peek: suspend (bytes: Int) -> ByteArray,
        open: suspend (url: String) -> MediaIo?,
        dateOf: (MediaIo) -> String?,
        policy: DashUrlPolicy = DashUrlPolicy.Default,
        maxManifestBytes: Long = MAX_MANIFEST_BYTES,
        maxSegmentBytes: Long = MAX_SEGMENT_BYTES,
    ): MediaIo? {
        val location = io.location
        if (!DashDetection.declared(io.contentType, location)) {
            if (!DashDetection.worthSniffing(io.contentType)) return null
            val head = peek(DashDetection.SNIFF_BYTES)
            if (!DashDetection.startsLikeMpd(head, head.size)) return null
        }
        val mpdUrl = requireNotNull(location) { "a reader with no address cannot be a DASH manifest" }
        val shown = shownUri(mpdUrl)
        DashManifestParser.requireAllowedScheme(mpdUrl, policy)
        suspend fun openChecked(url: String): MediaIo =
            open(url) ?: throw DashUrlRefusedException("${shownUri(url)} is not an http or https address")
        val manifest = manifest(readAllBounded(io, maxManifestBytes, "the manifest at $shown"), mpdUrl, policy, maxManifestBytes)
        val route = route(mpdUrl, manifest, policy, maxSegmentBytes)
        return route.reader(
            DashTransport(
                open = ::openChecked,
                fetch = { url, limit, what -> openChecked(url).use { readAllBounded(it, limit, what) } },
                refetch = { url ->
                    val body = openChecked(url).use { readAllBounded(it, maxManifestBytes, "the manifest at ${shownUri(url)}") }
                    manifest(body, url, policy, maxManifestBytes)
                },
                date = { url -> openChecked(url).use(dateOf) },
                bitsPerSecond = io::networkBitsPerSecond,
                release = io::close,
                // Every reader this item opens goes through [io], which collects their refusals.
                refusal = io::takeRefusal,
            ),
        )
    }

    /**
     * How [manifest] plays: through the HLS path when HLS can carry its Periods, otherwise as one
     * stream. Every refusal happens here, before any reader exists.
     */
    public fun route(mpdUrl: String, manifest: DashManifest, policy: DashUrlPolicy, maxSegmentBytes: Long): DashRoute {
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
            return HlsRoute(mpdUrl, manifest, DashHls.presentation(period, live = manifest.isDynamic, thumbnails = !manifest.isDynamic && manifest.periods.size == 1), policy)
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
            return FileRoute(file, Failover(mpdUrl, manifest.alternativeBaseUrls, policy))
        }
        // The plan itself is immutable and shared by every reader the route makes.
        return SegmentsRoute(
            DashManifestParser.segmentPlan(manifest, period, representation, policy),
            maxSegmentBytes,
            Failover(mpdUrl, manifest.alternativeBaseUrls, policy),
        )
    }

    /**
     * Throws [DashUrlRefusedException] when [policy] does not let a request of the door, shown as
     * [shown], be redirected to an address of [toScheme] and [toOrigin]. The manifest's own scheme
     * and origin are what a downgrade and [DashUrlPolicy.sameOriginOnly] are measured against. An
     * origin is the scheme, host and port as the transport connects to them.
     */
    public fun requireRedirectAllowed(
        policy: DashUrlPolicy,
        shown: String,
        manifestScheme: String,
        manifestOrigin: String,
        toScheme: String,
        toOrigin: String,
    ) {
        if (toScheme !in policy.allowedSchemes) {
            throw DashUrlRefusedException(
                "$shown redirects to scheme '$toScheme', which is not in ${policy.allowedSchemes.sorted()}",
            )
        }
        if (!policy.allowSchemeDowngrade && manifestScheme == "https" && toScheme != "https") {
            throw DashUrlRefusedException(
                "$shown, from an https manifest, redirects over '$toScheme'; set " +
                    "DashUrlPolicy(allowSchemeDowngrade = true) if that is genuinely intended",
            )
        }
        if (policy.sameOriginOnly && toOrigin != manifestOrigin) {
            throw DashUrlRefusedException(
                "$shown redirects to $toOrigin, and this policy is sameOriginOnly for $manifestOrigin",
            )
        }
    }

    /** What a reader needs to fail over between a manifest's locations: a fresh [DashFailover] per open. */
    private class Failover(val mpdUrl: String, val alternatives: List<DashBaseUrls>, val policy: DashUrlPolicy) {
        fun over(transport: DashTransport, failover: DashFailover = DashFailover(alternatives)): DashTransport =
            if (alternatives.isEmpty()) transport else transport.failingOver(failover, mpdUrl, policy)
    }

    /** One way a manifest plays, decided once; [reader] makes the reader of one open over a [DashTransport]. */
    public interface DashRoute {
        public suspend fun reader(transport: DashTransport): MediaIo
    }

    /** Through the HLS path: the reader stands in for an HLS master playlist. */
    private class HlsRoute(
        private val mpdUrl: String,
        private val manifest: DashManifest,
        private val presentation: DashHlsPresentation,
        private val policy: DashUrlPolicy,
    ) : DashRoute {
        override suspend fun reader(transport: DashTransport): MediaIo {
            // A live manifest fetched again may name its locations anew, and the reader keeps
            // the one it moved to for a base it still names.
            val failover = DashFailover(manifest.alternativeBaseUrls)
            val routed = if (manifest.isDynamic || manifest.alternativeBaseUrls.isNotEmpty()) {
                transport.failingOver(failover, mpdUrl, policy)
            } else {
                transport
            }
            return reader(routed, failover)
        }

        private fun reader(transport: DashTransport, failover: DashFailover): MediaIo = DashHlsMediaIo(
            presentation = presentation,
            manifest = manifest,
            manifestUrl = mpdUrl,
            policy = policy,
            openUrl = transport.open,
            onManifest = { fresh -> failover.update(fresh.alternativeBaseUrls) },
            refetch = if (manifest.isDynamic) transport.refetch else null,
            nowMicros = ::wallClockMicros,
            fetchDate = transport.date,
            bitsPerSecond = transport.bitsPerSecond,
            release = transport.release,
            refusal = transport.refusal,
        )
    }

    /** One file, read with range requests, so it seeks. */
    private class FileRoute(private val url: String, private val failover: Failover) : DashRoute {
        override suspend fun reader(transport: DashTransport): MediaIo =
            ReleasingMediaIo(failover.over(transport).open(url), transport.release)
    }

    /** One representation's segments as one forward stream. */
    private class SegmentsRoute(private val plan: DashSegmentPlan, private val maxSegmentBytes: Long, private val failover: Failover) : DashRoute {
        override suspend fun reader(transport: DashTransport): MediaIo {
            val routed = failover.over(transport)
            return ReleasingMediaIo(
                DashMediaIo(plan) { url -> routed.fetch(url, maxSegmentBytes, "the segment at ${shownUri(url)}") },
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

/** [uri] as a message may show it: with no credentials and no query. */
internal fun shownUri(uri: String): String = MediaItem(uri).label
