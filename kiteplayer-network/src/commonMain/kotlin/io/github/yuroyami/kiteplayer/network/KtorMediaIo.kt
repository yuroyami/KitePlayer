package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaIoResolver
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
import kotlinx.coroutines.launch

/**
 * Media bytes over http and https through Ktor (KPKMP 17.12 M1, the Ktor half): the engine's
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
 */
public class KtorMediaIo private constructor(
    private val client: HttpClient,
    private val ownsClient: Boolean,
    private val uri: String,
    private val requestHeaders: Map<String, String>,
    override val size: Long?,
    override val seekable: Boolean,
    firstBody: ByteReadChannel,
    firstJob: Job,
    private val scope: CoroutineScope,
) : MediaIo {

    private var position = 0L
    private var body: ByteReadChannel? = firstBody
    private var bodyJob: Job? = firstJob
    private var bodyPosition = 0L

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        if (length <= 0) return 0
        val knownSize = size
        if (knownSize != null && position >= knownSize) return -1
        val channel = body?.takeIf { bodyPosition == position } ?: openAt(position)
        val pulled = channel.readAvailable(into, offset, length)
        if (pulled < 0) return -1
        bodyPosition += pulled
        position += pulled
        return pulled
    }

    override suspend fun seek(position: Long) {
        // Lazy: the reposition is real at the next read, which reopens only when the current
        // stream is not already there. The engine's cache absorbs most seeks before this.
        this.position = position
    }

    override fun close() {
        body?.cancel()
        bodyJob?.cancel()
        scope.cancel()
        if (ownsClient) client.close()
    }

    /** One ranged GET at [target], streamed through a bounded pipe. */
    private fun openAt(target: Long): ByteReadChannel {
        body?.cancel()
        bodyJob?.cancel()
        val pipe = ByteChannel(autoFlush = true)
        bodyJob = scope.launch {
            try {
                client.prepareGet(uri) {
                    requestHeaders.forEach { (key, value) -> header(key, value) }
                    if (target > 0) header(HttpHeaders.Range, "bytes=$target-")
                }.execute { response ->
                    val ok = response.status == HttpStatusCode.PartialContent ||
                        (target == 0L && response.status == HttpStatusCode.OK)
                    if (!ok) {
                        throw KtorMediaIoException(
                            "server answered ${response.status} to a ranged read at byte $target of $uri",
                        )
                    }
                    response.bodyAsChannel().copyTo(pipe)
                    pipe.close()
                }
            } catch (failure: Throwable) {
                pipe.close(failure)
            }
        }
        body = pipe
        bodyPosition = target
        return pipe
    }

    public companion object {
        /**
         * Probes [uri] and returns a reader positioned at byte zero. The probe's own response
         * body becomes the first stream, so a plain open costs exactly one request.
         *
         * A null [client] creates a private one from the platform engine on the classpath and
         * closes it with the reader; a shared client stays the caller's to close.
         */
        public suspend fun open(
            uri: String,
            client: HttpClient? = null,
            headers: Map<String, String> = emptyMap(),
        ): KtorMediaIo {
            val ownsClient = client == null
            val http = client ?: HttpClient()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val probe = CompletableDeferred<Pair<Long?, Boolean>>()
            val pipe = ByteChannel(autoFlush = true)
            val job = scope.launch {
                try {
                    http.prepareGet(uri) {
                        headers.forEach { (key, value) -> header(key, value) }
                        header(HttpHeaders.Range, "bytes=0-")
                    }.execute { response ->
                        when (response.status) {
                            HttpStatusCode.PartialContent -> {
                                // Content-Range: bytes 0-last/total, total possibly "*".
                                val total = response.headers[HttpHeaders.ContentRange]
                                    ?.substringAfterLast('/')
                                    ?.toLongOrNull()
                                probe.complete(total to true)
                            }
                            HttpStatusCode.OK -> {
                                val total = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                                probe.complete(total to false)
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
            val (size, seekable) = try {
                probe.await()
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
                firstBody = pipe,
                firstJob = job,
                scope = scope,
            )
        }
    }
}

/** A typed failure from the http reader, surfaced to FFmpeg as an I/O error on the read. */
public class KtorMediaIoException(message: String) : Exception(message)

/**
 * The resolver that makes `player.open(MediaItem("https://..."))` just work: install it as
 * [io.github.yuroyami.kiteplayer.NetworkConfig.ioResolver] and every http and https uri opens
 * through [KtorMediaIo], everything else passes to the backend untouched.
 *
 * The lazily created client lives for the resolver's lifetime, which is normally the process:
 * exactly how OkHttp and NSURLSession want to be held.
 */
public class KtorMediaIoResolver(
    private val client: HttpClient? = null,
    private val headers: Map<String, String> = emptyMap(),
) : MediaIoResolver {

    private val shared: HttpClient by lazy { client ?: HttpClient() }

    override suspend fun resolve(uri: String): MediaIo? {
        val lower = uri.lowercase()
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return null
        return KtorMediaIo.open(uri, shared, headers)
    }
}
