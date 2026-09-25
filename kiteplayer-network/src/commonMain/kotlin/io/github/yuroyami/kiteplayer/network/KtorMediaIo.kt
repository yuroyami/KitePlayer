package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.copyTo
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
 */
public class KtorMediaIo private constructor(
    private val client: HttpClient,
    private val ownsClient: Boolean,
    private val uri: String,
    private val requestHeaders: Map<String, String>,
    override val size: Long?,
    override val seekable: Boolean,
    /** The strong entity tag of the first response, or null. Every ranged request asks for it. */
    private val entityTag: String?,
    firstBody: ByteReadChannel,
    firstJob: Job,
    private val scope: CoroutineScope,
    private val policy: HttpReaderPolicy,
) : MediaIo {

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

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw KtorMediaIoException("read after close")
        if (length <= 0) return 0
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
                throw KtorMediaIoException("the response from $uri ended at byte $position of $knownSize", retryable = true)
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
        return seekable || position == 0L
    }

    override suspend fun seek(position: Long) {
        if (closed) throw KtorMediaIoException("seek after close on $uri")
        // Lazy: the reposition is real at the next read, which reopens only when the current
        // stream is not already there. The engine's cache absorbs most seeks before this.
        this.position = position
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
        if (closed) throw KtorMediaIoException("openAt after close on $uri")
        dropBody()
        val pipe = ByteChannel(autoFlush = true)
        val answered = CompletableDeferred<Unit>()
        bodyJob = scope.launch {
            try {
                client.prepareGet(uri) {
                    requestHeaders.forEach { (key, value) -> header(key, value) }
                    if (target > 0) {
                        header(HttpHeaders.Range, "bytes=$target-")
                        // A server that honours this answers with the whole file, not a range, when
                        // the file changed since the first response.
                        entityTag?.let { header(HttpHeaders.IfRange, it) }
                    }
                }.execute { response ->
                    val ok = response.status == HttpStatusCode.PartialContent ||
                        (target == 0L && response.status == HttpStatusCode.OK)
                    if (!ok) {
                        val changed = if (entityTag != null && response.status == HttpStatusCode.OK) {
                            ", so the file changed since it was opened"
                        } else {
                            ""
                        }
                        throw KtorMediaIoException(
                            "server answered ${response.status} to a ranged read at byte $target of $uri$changed",
                            // A server error may pass. A refusal, or no ranges at all, will not.
                            retryable = response.status.value >= 500,
                        )
                    }
                    if (target > 0) {
                        // The bytes must continue where the reader stopped, in the same file, or
                        // they would splice in the wrong place.
                        val range = response.headers[HttpHeaders.ContentRange]
                        val start = range?.substringAfter("bytes ", "")?.substringBefore('-')?.trim()?.toLongOrNull()
                        if (start != null && start != target) {
                            throw KtorMediaIoException(
                                "server answered from byte $start to a ranged read at byte $target of $uri",
                            )
                        }
                        val total = range?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        if (total != null && size != null && total != size) {
                            throw KtorMediaIoException(
                                "the file at $uri changed since it was opened: it had $size bytes and has $total",
                            )
                        }
                        val tag = response.headers[HttpHeaders.ETag]
                        if (entityTag != null && tag != null && tag != entityTag) {
                            throw KtorMediaIoException(
                                "the file at $uri changed since it was opened: its entity tag was $entityTag and is $tag",
                            )
                        }
                    }
                    answered.complete(Unit)
                    response.bodyAsChannel().copyTo(pipe)
                    pipe.close()
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
                "no answer from $uri within ${policy.connectTimeout} for byte $target",
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

    public companion object {
        /**
         * Probes [uri] and returns a reader positioned at byte zero. The probe's own response
         * body becomes the first stream, so a plain open costs exactly one request.
         *
         * A null [client] creates a private one from the platform engine on the classpath and
         * closes it with the reader; a shared client stays the caller's to close. [policy] limits
         * every wait, this probe's included.
         */
        public suspend fun open(
            uri: String,
            client: HttpClient? = null,
            headers: Map<String, String> = emptyMap(),
            policy: HttpReaderPolicy = HttpReaderPolicy(),
        ): KtorMediaIo {
            val ownsClient = client == null
            val http = client ?: HttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val probe = CompletableDeferred<Probe>()
            val pipe = ByteChannel(autoFlush = true)
            val job = scope.launch {
                try {
                    http.prepareGet(uri) {
                        headers.forEach { (key, value) -> header(key, value) }
                        header(HttpHeaders.Range, "bytes=0-")
                    }.execute { response ->
                        // A weak tag cannot make a range request conditional, so only a strong one is kept.
                        val tag = response.headers[HttpHeaders.ETag]?.takeUnless { it.startsWith("W/") }
                        when (response.status) {
                            HttpStatusCode.PartialContent -> {
                                // Content-Range: bytes 0-last/total, total possibly "*".
                                val total = response.headers[HttpHeaders.ContentRange]
                                    ?.substringAfterLast('/')
                                    ?.toLongOrNull()
                                probe.complete(Probe(total, seekable = true, tag))
                            }
                            HttpStatusCode.OK -> {
                                val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                                probe.complete(Probe(total, seekable = false, tag))
                            }
                            else -> throw KtorMediaIoException("cannot open $uri: ${response.status}")
                        }
                        response.bodyAsChannel().copyTo(pipe)
                        pipe.close()
                    }
                } catch (failure: Throwable) {
                    pipe.close(failure)
                    probe.completeExceptionally(failure)
                }
            }
            val (size, seekable, entityTag) = try {
                withTimeoutOrNull(policy.connectTimeout) { probe.await() }
                    ?: throw KtorMediaIoException("no answer from $uri within ${policy.connectTimeout}")
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
                size = size,
                seekable = seekable,
                entityTag = entityTag,
                firstBody = pipe,
                firstJob = job,
                scope = scope,
                policy = policy,
            )
        }
    }
}

/** What the first response said about the file. */
private data class Probe(val size: Long?, val seekable: Boolean, val entityTag: String?)

/** A typed failure from the http reader, surfaced to FFmpeg as an I/O error on the read. */
public class KtorMediaIoException internal constructor(
    message: String,
    /** True when a reconnect may cure it: a timeout, a response that ended early, a server error. */
    internal val retryable: Boolean,
) : Exception(message) {
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
        return KtorMediaIo.open(uri, shared, merged, policy)
    }

    /** Closes the client this resolver created, if it ever created one. Idempotent. */
    override fun close() {
        created?.close()
        created = null
    }
}
