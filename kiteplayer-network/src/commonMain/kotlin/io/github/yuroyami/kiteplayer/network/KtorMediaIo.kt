package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.util.encodeBase64
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.TimeSource

/**
 * Media bytes over http and https through Ktor (the Ktor half): the engine's
 * FFmpeg profile deliberately vendors no TLS backend, so THIS is how https plays, with the OS
 * supplying TLS through the platform engine (OkHttp on Android and the JVM, NSURLSession on
 * Apple).
 *
 * Seekability is the server's Range support, probed once at [open] with a `bytes=0-` request:
 * a 206 answer means ranged reads work and the total size comes from Content-Range; a 200
 * answer means a forward-only stream sized by Content-Length when present. A seek reopens the
 * stream at the target with one ranged request; the engine's byte cache above this reader is
 * what makes small seek-backs free.
 *
 * Threading is [MediaIo]'s own contract: demux worker only, one call at a time. The streaming
 * body rides its own coroutine writing into a bounded pipe, so memory stays flat however far
 * the server runs ahead.
 *
 * Every wait has a limit from [HttpReaderPolicy]: the connection and the response headers of each
 * request, a seek's included, and the next bytes of a response that has started. A wait that
 * passes its limit fails with [KtorMediaIoException].
 *
 * A read that fails, times out or meets a response that ended early reconnects, with a `Range`
 * request at the byte it reached, after a backoff. The answer must start at that byte and belong
 * to the same file: the request carries `If-Range` with the entity tag of the first response, and
 * a changed tag or total size fails the read. Each reconnect is reported through [setWarningSink]
 * as [PlaybackWarning.SourceReconnecting]. A reader whose server has no ranges can start again
 * only before its first byte.
 *
 * [openRelated] opens the other addresses that the media names, such as the segments of an HLS
 * playlist, as readers of their own on the same client. Only http and https addresses open. The
 * item's own headers go only to the scheme, host and port of the item's address, because the
 * addresses come from the media. Headers that a [KtorMediaIoResolver] adds by default go to every
 * address. An open that times out, fails to connect or meets a server error tries again after a
 * backoff, as a read does.
 */
public class KtorMediaIo private constructor(
    private val client: HttpClient,
    private val ownsClient: Boolean,
    private val uri: String,
    private val requestHeaders: Map<String, String>,
    /** The headers a related reader gets: the defaults on every address, the rest on the item's own. */
    private val related: RelatedRequests,
    override val size: Long?,
    override val seekable: Boolean,
    override val location: String,
    override val contentType: String?,
    /** The strong entity tag of the first response, or null. Every ranged request asks for it. */
    private val entityTag: String?,
    /** The `Date` header of the first response, which a live DASH clock can read (#404), or null. */
    internal val date: String?,
    firstBody: ByteReadChannel,
    firstJob: Job,
    private val scope: CoroutineScope,
    private val policy: HttpReaderPolicy,
    /** Where a redirect of any request of this reader may lead, or null for Ktor's own rules. */
    private val redirects: RedirectRule?,
    /** How fast the network delivered this reader's bytes, shared with every reader it opened. */
    private val meter: DownloadMeter,
) : MediaIo {

    // What messages name instead of the URI, whose query may carry a signature (#241).
    private val shown = shownUri(uri)

    private var position = 0L
    private var warningSink: (PlaybackWarning) -> Unit = {}
    private var body: ByteReadChannel? = firstBody
    private var bodyJob: Job? = firstJob
    private var bodyPosition = 0L

    /**
     * Set by [close] and checked by every entry point.
     *
     * [close] cancels the scope. A read after that reached [openAt], whose `scope.launch` body
     * therefore never ran, so nothing ever wrote to the pipe and `readAvailable` suspended FOR
     * EVER: a hang with no error, no timeout and no thread to blame. Refusing typed is the only
     * honest answer, and it is the one a caller can see.
     */
    private var closed = false

    /** The bytes [peek] read, which the reads give back before any other. Null once they are given back or a seek drops them. */
    private var peeked: ByteArray? = null
    private var peekedAt = 0

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw KtorMediaIoException("read after close")
        if (length <= 0) return 0
        peeked?.let { head ->
            val count = minOf(length, head.size - peekedAt)
            head.copyInto(into, offset, peekedAt, peekedAt + count)
            peekedAt += count
            if (peekedAt == head.size) peeked = null
            if (count > 0) return count
        }
        var reconnects = 0
        while (true) {
            val failure = try {
                return readOnce(into, offset, length)
            } catch (failure: Throwable) {
                // The caller's own cancellation ends the read. Anything else is the connection's.
                currentCoroutineContext().ensureActive()
                failure
            }
            if (closed || reconnects >= policy.maxReconnects || !canResumeAfter(failure)) throw failure
            reconnects++
            dropBody()
            warningSink(
                PlaybackWarning.SourceReconnecting(position, reconnects, failure.message ?: failure.toString()),
            )
            delay(policy.backoff(reconnects))
        }
    }

    override fun setWarningSink(sink: (PlaybackWarning) -> Unit) {
        warningSink = sink
    }

    /**
     * How fast the network delivered the bytes of this reader and of every reader it opened. Only
     * the time a response waits for the network counts, from the request to the bytes, and a
     * response stops counting once it has waited for the player to read.
     */
    override fun networkBitsPerSecond(): Long? = meter.bitsPerSecond()

    /**
     * A new reader for [uri] on this reader's client, or null when [uri] is not http or https.
     * The reader reports through this reader's warning sink, and the caller closes it.
     */
    override suspend fun openRelated(uri: String): MediaIo? = openRelated(uri, redirects)

    /**
     * [openRelated], with every redirect of the new reader checked by [rule] instead of this
     * reader's own. The DASH door opens a manifest's segments this way when the automatic
     * transport found the manifest (#400).
     */
    internal suspend fun openRelated(uri: String, rule: RedirectRule?): KtorMediaIo? {
        if (closed) throw KtorMediaIoException("openRelated after close on $shown")
        if (!uri.isHttpUri()) return null
        var attempts = 0
        while (true) {
            val failure = try {
                return open(uri, client, ownsClient = false, related.headersFor(uri), related, policy, rule, meter)
                    .also { it.setWarningSink(warningSink) }
            } catch (failure: Throwable) {
                // The caller's own cancellation ends the open. Anything else is the connection's.
                currentCoroutineContext().ensureActive()
                failure
            }
            if (closed || attempts >= policy.maxReconnects || !mayOpenAgainAfter(failure, rule)) throw failure
            attempts++
            warningSink(PlaybackWarning.SourceReconnecting(0, attempts, failure.message ?: failure.toString()))
            delay(policy.backoff(attempts))
        }
    }

    /**
     * Reads up to [count] bytes from the start of the file, before any other read, and returns
     * them. The reads that follow give them back first, so looking at the start of a response
     * costs no second request (#400). Fewer come back only when the file is shorter.
     */
    internal suspend fun peek(count: Int): ByteArray {
        check(position == 0L && peeked == null) { "a reader can only peek before its first read" }
        val head = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = read(head, filled, count - filled)
            when {
                read < 0 -> break
                read == 0 -> delay(1)
                else -> filled += read
            }
        }
        val bytes = if (filled == count) head else head.copyOf(filled)
        if (bytes.isNotEmpty()) {
            peeked = bytes
            peekedAt = 0
        }
        return bytes
    }

    /** True when a new attempt may open what [failure] refused: a timeout, a dropped connection or a server error. */
    private fun mayOpenAgainAfter(failure: Throwable, rule: RedirectRule?): Boolean {
        if (failure is KtorMediaIoException && !failure.retryable) return false
        return rule?.isRefusal(failure) != true
    }

    /** One read from the current response, or from a new one at [position]. */
    private suspend fun readOnce(into: ByteArray, offset: Int, length: Int): Int {
        val knownSize = size
        if (knownSize != null && position >= knownSize) return -1
        val channel = body?.takeIf { bodyPosition == position } ?: openAt(position)
        // A response that stops sending is a failure, not a wait for ever.
        val pulled = withTimeoutOrNull(policy.readTimeout) { channel.readAvailable(into, offset, length) }
        if (pulled == null) {
            dropBody()
            throw KtorMediaIoException("no bytes for ${policy.readTimeout} at byte $position", retryable = true)
        }
        if (pulled < 0) {
            // A response that ends before the declared size is a dropped connection.
            if (knownSize != null && position < knownSize) {
                dropBody()
                throw KtorMediaIoException("the response from $shown ended at byte $position of $knownSize", retryable = true)
            }
            return -1
        }
        bodyPosition += pulled
        position += pulled
        return pulled
    }

    /**
     * True when a reconnect may cure [failure]: a timeout, a dropped connection or a server error,
     * at a byte that the server can resume from. Without ranges, only a read that has not reached
     * its first byte can start again.
     */
    private fun canResumeAfter(failure: Throwable): Boolean {
        if (failure is KtorMediaIoException && !failure.retryable) return false
        if (redirects?.isRefusal(failure) == true) return false
        return seekable || position == 0L
    }

    override suspend fun seek(position: Long) {
        if (closed) throw KtorMediaIoException("seek after close on $shown")
        // Lazy: the reposition is real at the next read, which reopens only when the current
        // stream is not already there. The engine's cache absorbs most seeks before this.
        this.position = position
        peeked = null
    }

    /** Idempotent: closing twice is a no-op, and every later read or seek refuses typed. */
    override fun close() {
        if (closed) return
        closed = true
        body?.cancel()
        bodyJob?.cancel()
        scope.cancel()
        if (ownsClient) client.close()
    }

    /**
     * One ranged GET at [target], streamed through a bounded pipe. Returns once the response has
     * begun, which [HttpReaderPolicy.connectTimeout] bounds: that is what limits a seek.
     */
    private suspend fun openAt(target: Long): ByteReadChannel {
        if (closed) throw KtorMediaIoException("openAt after close on $shown")
        dropBody()
        val pipe = ByteChannel(autoFlush = true)
        val answered = CompletableDeferred<Unit>()
        bodyJob = scope.launch {
            val sent = TimeSource.Monotonic.markNow()
            try {
                guarded(client, redirects) { mark ->
                    client.prepareGet(uri) {
                        mark()
                        requestHeaders.forEach { (key, value) -> header(key, value) }
                        // A rewind to zero asks for a range too, so its answer is checked like any other.
                        header(HttpHeaders.Range, "bytes=$target-")
                        // A server that honours this answers with the whole file, not a range, when
                        // the file changed since the first response.
                        entityTag?.let { header(HttpHeaders.IfRange, it) }
                    }.execute { response ->
                        redirects?.refuseHidden(response, shown)
                        val ok = response.status == HttpStatusCode.PartialContent ||
                            (target == 0L && response.status == HttpStatusCode.OK)
                        if (!ok) {
                            val changed = if (entityTag != null && response.status == HttpStatusCode.OK) {
                                ", so the file changed since it was opened"
                            } else {
                                ""
                            }
                            throw KtorMediaIoException(
                                "server answered ${response.status} to a ranged read at byte $target of $shown$changed",
                                // A server error may pass. A refusal, or no ranges at all, will not.
                                retryable = response.status.value >= 500,
                            )
                        }
                        // The bytes must start where the reader stands, in the same file, or they would
                        // splice in the wrong place (#283). RFC 9110, 14.4: Content-Range names the
                        // range the body holds, so a 206 without a valid one proves nothing.
                        val total = if (response.status == HttpStatusCode.PartialContent) {
                            val range = parseContentRange(response.headers[HttpHeaders.ContentRange])
                                ?: throw KtorMediaIoException(
                                    "server answered a ranged read at byte $target of $shown with no valid Content-Range",
                                )
                            if (range.first != target) {
                                throw KtorMediaIoException(
                                    "server answered from byte ${range.first} to a ranged read at byte $target of $shown",
                                )
                            }
                            range.complete
                        } else {
                            response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                        }
                        if (size != null && total != size) {
                            throw KtorMediaIoException(
                                "the file at $shown changed since it was opened: it had $size bytes and has ${total ?: "an unknown size"}",
                            )
                        }
                        val tag = response.headers[HttpHeaders.ETag]
                        if (entityTag != null && tag != null && tag != entityTag) {
                            throw KtorMediaIoException(
                                "the file at $shown changed since it was opened: its entity tag was $entityTag and is $tag",
                            )
                        }
                        answered.complete(Unit)
                        copyMeasured(response.bodyAsChannel(), pipe, meter, sent)
                        pipe.close()
                    }
                }
            } catch (failure: Throwable) {
                answered.completeExceptionally(failure)
                pipe.close(failure)
            }
        }
        body = pipe
        bodyPosition = target
        val answeredInTime = try {
            withTimeoutOrNull(policy.connectTimeout) { answered.await() } != null
        } catch (failure: Throwable) {
            dropBody()
            throw failure
        }
        if (!answeredInTime) {
            dropBody()
            throw KtorMediaIoException(
                "no answer from $shown within ${policy.connectTimeout} for byte $target",
                retryable = true,
            )
        }
        return pipe
    }

    /** Gives up the current response. The next read opens a new one at [position]. */
    private fun dropBody() {
        body?.cancel()
        bodyJob?.cancel()
        body = null
        bodyJob = null
    }

    /** Opens readers. */
    public companion object {
        /**
         * Probes [uri] and returns a reader positioned at byte zero. The probe's own response
         * body becomes the first stream, so a plain open costs exactly one request.
         *
         * A null [client] creates a private one from the platform engine on the classpath and
         * closes it with the reader; a shared client stays the caller's to close. [policy] limits
         * every wait, this probe's included.
         */
        @Throws(Exception::class)
        public suspend fun open(
            uri: String,
            client: HttpClient? = null,
            headers: Map<String, String> = emptyMap(),
            policy: HttpReaderPolicy = HttpReaderPolicy(),
        ): KtorMediaIo = open(uri, client, headers, policy, redirects = null)

        /**
         * [open], with every redirect of every request this reader makes checked by [redirects]. A
         * [meter] shared by several readers measures the network rate of all of them together.
         */
        internal suspend fun open(
            uri: String,
            client: HttpClient?,
            headers: Map<String, String>,
            policy: HttpReaderPolicy,
            redirects: RedirectRule?,
            defaultHeaders: Map<String, String> = emptyMap(),
            meter: DownloadMeter = DownloadMeter(),
        ): KtorMediaIo {
            // A user name and password in the address become its login, sent to the item's own
            // origin as the item's headers are, and the address is requested without them (#448).
            val login = basicLogin(uri)
            val requested = login?.uri ?: uri
            val itemHeaders = login?.let { withLogin(headers, it) } ?: headers
            val related = RelatedRequests(requested, defaultHeaders, itemHeaders)
            return open(
                requested, client ?: HttpClient(), ownsClient = client == null, itemHeaders, related, policy, redirects, meter,
            )
        }

        /** Probes [uri] on [http], which the new reader closes when it [ownsClient]. */
        private suspend fun open(
            uri: String,
            http: HttpClient,
            ownsClient: Boolean,
            headers: Map<String, String>,
            related: RelatedRequests,
            policy: HttpReaderPolicy,
            redirects: RedirectRule?,
            meter: DownloadMeter,
        ): KtorMediaIo {
            // An address that the media names can carry a login of its own, as the item's can.
            basicLogin(uri)?.let { login ->
                return open(login.uri, http, ownsClient, withLogin(headers, login), related, policy, redirects, meter)
            }
            val shown = shownUri(uri)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val probe = CompletableDeferred<Probe>()
            val pipe = ByteChannel(autoFlush = true)
            val job = scope.launch {
                val sent = TimeSource.Monotonic.markNow()
                try {
                    guarded(http, redirects) { mark ->
                        http.prepareGet(uri) {
                            mark()
                            headers.forEach { (key, value) -> header(key, value) }
                            header(HttpHeaders.Range, "bytes=0-")
                        }.execute { response ->
                            redirects?.refuseHidden(response, shown)
                            // A weak tag cannot make a range request conditional, so only a strong one is kept.
                            val tag = response.headers[HttpHeaders.ETag]?.takeUnless { it.startsWith("W/") }
                            // The request that answered, after every redirect Ktor followed.
                            val location = response.call.request.url.toString()
                            val type = response.headers[HttpHeaders.ContentType]
                            val date = response.headers[HttpHeaders.Date]
                            when (response.status) {
                                HttpStatusCode.PartialContent -> {
                                    // Content-Range: bytes 0-last/total, total possibly "*". A range that
                                    // does not start at zero would be read as the start of the file (#283).
                                    val range = parseContentRange(response.headers[HttpHeaders.ContentRange])
                                    if (range == null || range.first != 0L) {
                                        throw KtorMediaIoException(
                                            "cannot open $shown: it answered the read from byte 0 with the range " +
                                                "${response.headers[HttpHeaders.ContentRange]}",
                                        )
                                    }
                                    probe.complete(Probe(range.complete, seekable = true, tag, location, type, date))
                                }
                                HttpStatusCode.OK -> {
                                    val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                                    probe.complete(Probe(total, seekable = false, tag, location, type, date))
                                }
                                else -> throw KtorMediaIoException(
                                    "cannot open $shown: ${response.status}",
                                    // A server error, an overloaded server or a slow request may pass.
                                    retryable = response.status.value >= 500 ||
                                        response.status == HttpStatusCode.RequestTimeout ||
                                        response.status == HttpStatusCode.TooManyRequests,
                                )
                            }
                            copyMeasured(response.bodyAsChannel(), pipe, meter, sent)
                            pipe.close()
                        }
                    }
                } catch (failure: Throwable) {
                    pipe.close(failure)
                    probe.completeExceptionally(failure)
                }
            }
            val answer = try {
                withTimeoutOrNull(policy.connectTimeout) { probe.await() }
                    ?: throw KtorMediaIoException("no answer from $shown within ${policy.connectTimeout}", retryable = true)
            } catch (failure: Throwable) {
                scope.cancel()
                if (ownsClient) http.close()
                throw failure
            }
            return KtorMediaIo(
                client = http,
                ownsClient = ownsClient,
                uri = uri,
                requestHeaders = headers,
                related = related,
                size = answer.size,
                seekable = answer.seekable,
                location = answer.location,
                contentType = answer.contentType,
                entityTag = answer.entityTag,
                date = answer.date,
                firstBody = pipe,
                firstJob = job,
                scope = scope,
                policy = policy,
                redirects = redirects,
                meter = meter,
            )
        }
    }
}

/** An address's user name and password as a Basic login, and the address without them. */
internal class BasicLogin(val uri: String, val authorization: String)

/**
 * The login in [uri], an http or https address written `scheme://user:password@host/...`, or null
 * when it has none (#448).
 *
 * FFmpeg's own http reader logs in with such an address, and so do ffplay, VLC and mpv, which is
 * how private Icecast servers, NAS media servers and cameras are commonly given. Ktor sends the
 * login only through its own basic auth call, and OkHttp keeps it in the address unsent, so the
 * reader lifts it out into a Basic `Authorization` header here. The header is one of the item's
 * own, so it goes only to the item's scheme, host and port, and Ktor drops it on a redirect to
 * another host. The user name and the password are read percent-decoded, as the address spells
 * them, and sent as UTF-8.
 */
internal fun basicLogin(uri: String): BasicLogin? {
    val url = runCatching { Url(uri) }.getOrNull() ?: return null
    if (url.protocol != URLProtocol.HTTP && url.protocol != URLProtocol.HTTPS) return null
    val user = url.user ?: return null
    if (user.isEmpty() && url.password.isNullOrEmpty()) return null
    val bare = URLBuilder(url).apply {
        this.user = null
        this.password = null
    }.buildString()
    val token = "$user:${url.password.orEmpty()}".encodeToByteArray().encodeBase64()
    return BasicLogin(bare, "Basic $token")
}

/** [headers] with [login] added, unless they carry an `Authorization` of their own, which wins. */
internal fun withLogin(headers: Map<String, String>, login: BasicLogin): Map<String, String> =
    if (headers.keys.any { it.equals(HttpHeaders.Authorization, ignoreCase = true) }) {
        headers
    } else {
        headers + (HttpHeaders.Authorization to login.authorization)
    }

/** What the first response said about the file. */
private class Probe(
    val size: Long?,
    val seekable: Boolean,
    val entityTag: String?,
    val location: String,
    val contentType: String?,
    val date: String?,
)

/**
 * The headers of the requests that a reader's related readers send. [defaults] go to every address.
 * [itemHeaders] go only to the scheme, host and port of [itemUri], because the addresses come from
 * the media and a header there may be a credential.
 */
internal class RelatedRequests(
    itemUri: String,
    private val defaults: Map<String, String>,
    private val itemHeaders: Map<String, String>,
) {
    private val itemOrigin = originOrNull(itemUri)

    fun headersFor(uri: String): Map<String, String> =
        if (itemOrigin != null && originOrNull(uri) == itemOrigin) itemHeaders else defaults

    private fun originOrNull(uri: String): String? = runCatching { originOf(Url(uri)) }.getOrNull()
}

/** A typed failure from the http reader, surfaced to FFmpeg as an I/O error on the read. */
public class KtorMediaIoException internal constructor(
    message: String,
    /** True when a reconnect may cure it: a timeout, a response that ended early, a server error. */
    internal val retryable: Boolean,
) : Exception(message) {
    /** A failure that a reconnect cannot cure. */
    public constructor(message: String) : this(message, retryable = false)
}

/**
 * Explicit HTTP/HTTPS resolver for a shared [HttpClient], default request headers or an
 * [HttpReaderPolicy] of its own; the automatic provider uses the default policy. Install
 * it as [io.github.yuroyami.kiteplayer.NetworkConfig.ioResolver]; other URIs pass to the backend.
 * Adding this module already supplies automatic transport for standard opens, whose private
 * clients close with each reader. Use this resolver when the application owns a shared client
 * or wants resolver-wide defaults. Per-item headers override these defaults.
 *
 * The defaults also go to every related address a reader opens, such as an HLS segment on another
 * server. Per-item headers go only to the scheme, host and port of the item's own address.
 *
 * An address that answers with a DASH manifest plays as a DASH presentation, as the automatic
 * provider plays it: the manifest is recognised by its content type, by a path that ends in `.mpd`,
 * or by its root element when the type leaves room for one, and its segments open on this
 * resolver's client.
 *
 * The lazily created client lives for the resolver's lifetime, which is normally the process:
 * exactly how OkHttp and NSURLSession want to be held. A resolver with a shorter life closes
 * the client it created through [close] (it used to leak the engine's connection
 * and thread pools); a caller-supplied client stays the caller's to close, as everywhere else.
 */
public class KtorMediaIoResolver(
    private val client: HttpClient? = null,
    private val headers: Map<String, String> = emptyMap(),
    /** How long each reader that this resolver makes waits for the server. */
    private val policy: HttpReaderPolicy = HttpReaderPolicy(),
) : MediaIoResolver, AutoCloseable {

    private var created: HttpClient? = null

    private val shared: HttpClient by lazy { client ?: HttpClient().also { created = it } }

    override suspend fun resolve(uri: String): MediaIo? = resolve(uri, emptyMap())

    /** Per-item headers override this resolver's defaults, with case-insensitive HTTP names. */
    override suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo? {
        if (!uri.isHttpUri()) return null
        val itemNames = headers.keys.map { it.lowercase() }.toSet()
        val merged = this.headers.filterKeys { it.lowercase() !in itemNames } + headers
        return playableReader(KtorMediaIo.open(uri, shared, merged, policy, redirects = null, defaultHeaders = this.headers))
    }

    /** Closes the client this resolver created, if it ever created one. Idempotent. */
    override fun close() {
        created?.close()
        created = null
    }
}

/**
 * [uri] as messages show it: the file name, with no host, query or fragment, as `MediaItem.label`
 * cuts it. Warnings and exceptions reach application logs, and a signed URL there is a leaked
 * credential.
 */
internal fun shownUri(uri: String): String = MediaItem(uri).label

/** A `Content-Range: bytes first-last/complete` header. [complete] is null for an unknown length. */
internal data class ContentRange(val first: Long, val last: Long, val complete: Long?)

/** Parses [header] as RFC 9110, 14.4 defines a byte range, or returns null when it is missing or malformed. */
internal fun parseContentRange(header: String?): ContentRange? {
    val value = header?.trim() ?: return null
    if (!value.startsWith("bytes ", ignoreCase = true)) return null
    val spec = value.substring("bytes ".length).trim()
    val range = spec.substringBefore('/', missingDelimiterValue = "")
    val completeText = spec.substringAfter('/', missingDelimiterValue = "").trim()
    val first = range.substringBefore('-', missingDelimiterValue = "").trim().toLongOrNull() ?: return null
    val last = range.substringAfter('-', missingDelimiterValue = "").trim().toLongOrNull() ?: return null
    if (first < 0 || last < first) return null
    val complete = if (completeText == "*") null else completeText.toLongOrNull() ?: return null
    if (complete != null && last >= complete) return null
    return ContentRange(first, last, complete)
}
