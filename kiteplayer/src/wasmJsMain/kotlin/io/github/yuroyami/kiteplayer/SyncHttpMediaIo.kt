@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.yuroyami.kiteplayer

import kotlin.js.JsAny
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Answers an `http`, `https` or `blob` address in a player that runs in a Web Worker (#100) with a
 * [SyncHttpMediaIo], and every other address with null, which leaves it to the backend.
 */
internal class SyncHttpResolver(private val timeout: Duration) : MediaIoResolver {

    override suspend fun resolve(uri: String): MediaIo? = resolve(uri, emptyMap())

    override suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo? {
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "blob") return null
        return SyncHttpMediaIo(uri, headers, timeout).also { it.start() }
    }
}

/**
 * Reads an address with synchronous range requests, which a browser allows only in a worker.
 *
 * The web backend cannot wait for a reader that suspends, so it refuses one. A synchronous request
 * finishes before [read] returns, so to the backend this reader looks like one whose bytes are
 * already resident, and the worker's thread waits for the network instead. That is legal in a
 * worker and needs no isolation headers.
 *
 * Each request asks for a window of the file. The window doubles while the reads run forward, up
 * to [MAX_WINDOW_BYTES], and starts small again after a seek, so a file read from the start takes
 * few requests and a seek does not download what it skips. A server that ignores the range sends
 * the whole file, and every read is then answered from that.
 *
 * The bytes stay in a JS array, and each read copies only what it asked for into Kotlin, as one
 * Latin-1 string: one crossing per read rather than one per byte.
 */
internal class SyncHttpMediaIo(
    private val url: String,
    private val headers: Map<String, String>,
    private val timeout: Duration,
    /** Shared with the readers that [openRelated] makes, so the rate counts an HLS stream's segments. */
    private val meter: SyncDownloadMeter = SyncDownloadMeter(),
) : MediaIo {

    private val state: JsAny = xhrState(headers.toJsObject(), timeout.inWholeMilliseconds.toDouble())
    private var position = 0L
    private var nextWindow = FIRST_WINDOW_BYTES
    private var closed = false

    private val windowStart: Long get() = xhrWindowStart(state).toLong()
    private val windowEnd: Long get() = windowStart + xhrWindowLength(state)

    /** The size the server stated, or the length of a whole body sent for a range it ignored. */
    override val size: Long? get() = xhrTotal(state).takeIf { it >= 0.0 }?.toLong()

    override val seekable: Boolean get() = true

    override val location: String? get() = xhrLocation(state)

    override val contentType: String? get() = xhrContentType(state)

    /**
     * Fetches the first window, so a failure to reach the address fails the open, as other readers
     * do. The web backend stages a source only when it knows the size. A server on another origin
     * may hide `Content-Range` from the page, so a size it did not state is asked for once more,
     * as the `Content-Length` of a `HEAD` request, which every origin may read.
     */
    fun start() {
        fetch(0L)
        if (size == null) xhrHeadLength(state, url)
    }

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "the reader for $url is closed" }
        if (length == 0) return 0
        size?.let { if (position >= it) return -1 }
        if (position < windowStart || position >= windowEnd) {
            fetch(position)
            // An empty answer, or a refused range, is the end of the file.
            if (position >= windowEnd) return -1
        }
        val count = minOf(length.toLong(), windowEnd - position).toInt()
        val bytes = xhrSlice(state, (position - windowStart).toInt(), count)
        for (i in 0 until count) into[offset + i] = bytes[i].code.toByte()
        position += count
        return count
    }

    override suspend fun seek(position: Long) {
        require(position >= 0) { "cannot seek to $position" }
        this.position = position
    }

    /**
     * A reader of the same kind for [uri], which an HLS playlist names its playlists, segments and
     * keys with (#546). The item's headers go only to the origin this reader's own address has,
     * because another origin was never meant to see them. Null for any scheme but `http`, `https`
     * and `blob`.
     */
    override suspend fun openRelated(uri: String): MediaIo? {
        check(!closed) { "the reader for $url is closed" }
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "blob") return null
        val own = originOf(uri) == originOf(url)
        return SyncHttpMediaIo(uri, if (own) headers else emptyMap(), timeout, meter).also { it.start() }
    }

    override fun networkBitsPerSecond(): Long? = meter.bitsPerSecond()

    override fun close() {
        if (closed) return
        closed = true
        xhrRelease(state)
    }

    private fun fetch(from: Long) {
        nextWindow = if (from == windowEnd && from > 0) (nextWindow * 2).coerceAtMost(MAX_WINDOW_BYTES) else FIRST_WINDOW_BYTES
        val asked = TimeSource.Monotonic.markNow()
        when (val status = xhrFetch(state, url, from.toDouble(), nextWindow.toDouble())) {
            // The whole request waits for the network, so all of its time counts.
            200, 206 -> meter.add(xhrWindowLength(state), asked.elapsedNow())
            416 -> Unit
            TIMED_OUT -> throw PlaybackException(PlaybackError.SourceStalled(url, timeout))
            0 -> throw PlaybackException(PlaybackError.SourceUnavailable(url, null, xhrError(state) ?: "the request failed"))
            else -> throw PlaybackException(PlaybackError.SourceUnavailable(url, null, "HTTP $status"))
        }
    }

    private companion object {
        const val FIRST_WINDOW_BYTES = 1L shl 20
        const val MAX_WINDOW_BYTES = 16L shl 20

        /** What [xhrFetch] answers when the request ran out of time. */
        const val TIMED_OUT = -1
    }
}

/** The scheme, host and port of [address], which a browser resolves against the worker's own. */
@JsFun("(address) => { try { return new URL(address, self.location.href).origin; } catch (e) { return address; } }")
private external fun originOf(address: String): String

/**
 * How fast the network delivered a reader's bytes and those of the readers it opened, as the
 * player's other network reader measures it: each [HALF_LIFE_BYTES] halves the weight of what came
 * before. A worker has one thread, so nothing here is shared between two.
 */
internal class SyncDownloadMeter {
    private var bytes = 0.0
    private var nanos = 0.0
    private var counted = 0L

    /** [count] bytes arrived after a wait of [took] for the network. */
    fun add(count: Int, took: Duration) {
        if (count <= 0) return
        val keep = 0.5.pow(count / HALF_LIFE_BYTES)
        bytes = bytes * keep + count
        nanos = nanos * keep + took.inWholeNanoseconds.coerceAtLeast(0L)
        counted += count
    }

    /** Bits per second, recent bytes weighing most, or null before [MIN_BYTES] were measured. */
    fun bitsPerSecond(): Long? {
        if (counted < MIN_BYTES || nanos <= 0.0) return null
        return (bytes * 8.0 * 1_000_000_000.0 / nanos).toLong()
    }

    private companion object {
        const val HALF_LIFE_BYTES = 1_048_576.0

        /** Bytes to measure before the figure means anything: about one segment of a low variant. */
        const val MIN_BYTES = 524_288L
    }
}

@JsFun(
    """(headers, timeout) => ({
      headers: headers, timeout: timeout, buf: new Uint8Array(0), start: 0, total: -1,
      url: null, type: null, error: null,
    })""",
)
private external fun xhrState(headers: JsAny, timeoutMillis: Double): JsAny

/**
 * One synchronous request for [count] bytes from [from]. Answers the status, 0 when the request
 * did not complete, or -1 when it ran out of time. On 200 or 206 the answer becomes the window.
 */
@JsFun(
    """(s, url, from, count) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', url, false);
      xhr.responseType = 'arraybuffer';
      xhr.timeout = s.timeout;
      for (const k of Object.keys(s.headers)) { try { xhr.setRequestHeader(k, s.headers[k]); } catch (e) {} }
      xhr.setRequestHeader('Range', 'bytes=' + from + '-' + (from + count - 1));
      try { xhr.send(); } catch (e) {
        s.error = (e && e.message) ? e.message : String(e);
        return (e && e.name === 'TimeoutError') ? -1 : 0;
      }
      const status = xhr.status;
      if (status === 200 || status === 206) {
        s.buf = new Uint8Array(xhr.response || new ArrayBuffer(0));
        s.url = xhr.responseURL || url;
        s.type = xhr.getResponseHeader('Content-Type');
        if (status === 200) {
          s.start = 0;
          s.total = s.buf.length;
        } else {
          const range = xhr.getResponseHeader('Content-Range');
          const first = range ? /bytes\s+(\d+)-/.exec(range) : null;
          const total = range ? /\/(\d+)\s*$/.exec(range) : null;
          s.start = first ? Number(first[1]) : from;
          if (total) s.total = Number(total[1]);
          // Fewer bytes than were asked for, with no stated size: the file ends there.
          else if (s.buf.length < count) s.total = s.start + s.buf.length;
        }
      } else if (status === 416) {
        s.buf = new Uint8Array(0);
        s.start = from;
      }
      return status;
    }""",
)
private external fun xhrFetch(state: JsAny, url: String, from: Double, count: Double): Int

/** One synchronous `HEAD` request, whose `Content-Length` becomes the size. Leaves the size unknown on any failure. */
@JsFun(
    """(s, url) => {
      const xhr = new XMLHttpRequest();
      xhr.open('HEAD', url, false);
      xhr.timeout = s.timeout;
      for (const k of Object.keys(s.headers)) { try { xhr.setRequestHeader(k, s.headers[k]); } catch (e) {} }
      try { xhr.send(); } catch (e) { return; }
      const length = xhr.getResponseHeader('Content-Length');
      if (xhr.status === 200 && length !== null && /^\d+$/.test(length.trim())) s.total = Number(length.trim());
    }""",
)
private external fun xhrHeadLength(state: JsAny, url: String)

@JsFun("(s) => s.start")
private external fun xhrWindowStart(state: JsAny): Double

@JsFun("(s) => s.buf.length")
private external fun xhrWindowLength(state: JsAny): Int

@JsFun("(s) => s.total")
private external fun xhrTotal(state: JsAny): Double

@JsFun("(s) => s.url")
private external fun xhrLocation(state: JsAny): String?

@JsFun("(s) => s.type")
private external fun xhrContentType(state: JsAny): String?

@JsFun("(s) => s.error")
private external fun xhrError(state: JsAny): String?

// Latin-1 by construction: each byte becomes one code unit below 0x100, in slices small enough for
// the argument limit of a call.
@JsFun(
    """(s, at, count) => {
      const parts = [];
      for (let i = 0; i < count; i += 8192) {
        parts.push(String.fromCharCode.apply(null, s.buf.subarray(at + i, at + Math.min(count, i + 8192))));
      }
      return parts.join('');
    }""",
)
private external fun xhrSlice(state: JsAny, at: Int, count: Int): String

@JsFun("(s) => { s.buf = new Uint8Array(0); }")
private external fun xhrRelease(state: JsAny)
