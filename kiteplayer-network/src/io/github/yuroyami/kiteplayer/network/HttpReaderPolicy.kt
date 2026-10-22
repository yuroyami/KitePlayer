package io.github.yuroyami.kiteplayer.network

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How long the HTTP reader, [KtorMediaIo], waits for a server, and how it recovers when a
 * connection drops.
 *
 * The reader enforces these limits itself, with coroutine timeouts, so they hold with every Ktor
 * engine and with a client that the application supplies. A wait that passes its limit fails with
 * [KtorMediaIoException], and during a read that failure makes the reader reconnect.
 */
public data class HttpReaderPolicy(
    /**
     * The longest wait for the connection and the response headers of one request. A seek sends a
     * new request, so this limits a seek too.
     */
    val connectTimeout: Duration = 10.seconds,
    /** The longest wait for the next bytes of a response that has started. */
    val readTimeout: Duration = 10.seconds,
    /**
     * How many times one read may reconnect after a failure, a read timeout or a response that
     * ended early. Each reconnect is a `Range` request at the byte the reader reached. Zero turns
     * recovery off. The count belongs to one read, so a connection that drops now and then never
     * uses it up.
     */
    val maxReconnects: Int = 5,
    /** The wait before the first reconnect of a read. Each later wait is twice as long. */
    val initialBackoff: Duration = 500.milliseconds,
    /** The longest wait between two reconnects. */
    val maxBackoff: Duration = 4.seconds,
) {
    init {
        require(connectTimeout > Duration.ZERO) { "connectTimeout must be positive, was $connectTimeout" }
        require(readTimeout > Duration.ZERO) { "readTimeout must be positive, was $readTimeout" }
        require(maxReconnects >= 0) { "maxReconnects must not be negative, was $maxReconnects" }
        require(initialBackoff >= Duration.ZERO) { "initialBackoff must not be negative, was $initialBackoff" }
        require(maxBackoff >= initialBackoff) {
            "maxBackoff ($maxBackoff) must not be shorter than initialBackoff ($initialBackoff)"
        }
    }

    /** The wait before reconnect number [reconnect] of one read, counting from one. */
    internal fun backoff(reconnect: Int): Duration {
        var wait = initialBackoff
        repeat(reconnect - 1) { wait = minOf(wait * 2, maxBackoff) }
        return minOf(wait, maxBackoff)
    }
}
