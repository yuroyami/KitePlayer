package io.github.yuroyami.kiteplayer.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long the HTTP reader, [KtorMediaIo], waits for a server.
 *
 * The reader enforces these limits itself, with coroutine timeouts, so they hold with every Ktor
 * engine and with a client that the application supplies. A wait that passes its limit fails with
 * [KtorMediaIoException].
 */
public data class HttpReaderPolicy(
    /**
     * The longest wait for the connection and the response headers of one request. A seek sends a
     * new request, so this limits a seek too.
     */
    val connectTimeout: Duration = 10.seconds,
    /** The longest wait for the next bytes of a response that has started. */
    val readTimeout: Duration = 10.seconds,
) {
    init {
        require(connectTimeout > Duration.ZERO) { "connectTimeout must be positive, was $connectTimeout" }
        require(readTimeout > Duration.ZERO) { "readTimeout must be positive, was $readTimeout" }
    }
}
