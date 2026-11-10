@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, KitePlayerInternalApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.network.HlsTtml
import io.github.yuroyami.kiteplayer.network.dash.DashDoor
import io.github.yuroyami.kiteplayer.network.dash.DashUrlPolicy
import kotlin.js.JsAny
import kotlin.math.pow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Answers an `http`, `https` or `blob` address in a player that runs in a Web Worker (#100) with a
 * [SyncHttpMediaIo], and every other address with null, which leaves it to the backend.
 *
 * An address that answers with a DASH manifest gets the reader of that presentation, and an HLS
 * master playlist with TTML subtitles gets the reader that serves them as WebVTT, as the player's
 * other network transport gives them (#546).
 */
internal class SyncHttpResolver(private val timeout: Duration) : MediaIoResolver {

    override suspend fun resolve(uri: String): MediaIo? = resolve(uri, emptyMap())

    override suspend fun resolve(uri: String, headers: Map<String, String>): MediaIo? {
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "blob") return null
        val io = SyncHttpMediaIo(uri, headers, timeout).also { it.start() }
        return try {
            playable(io)
        } catch (failure: Throwable) {
            io.close()
            throw failure
        }
    }

    private suspend fun playable(io: SyncHttpMediaIo): MediaIo {
        val location = io.location ?: return io
        if (HlsTtml.declaredHls(io.contentType, location)) return HlsTtml.readerIfTtml(io, io::peek) ?: io
        val policy = DashUrlPolicy.Default
        return DashDoor.readerIfManifest(
            io = io,
            peek = io::peek,
            // Not asked for until something reads it: the door opens a file and then seeks to the
            // range it wants, and a request made at the open would download the file's start first.
            open = { url -> io.openRelated(url, eager = false) { asked, answered -> requireRedirect(policy, location, asked, answered) } },
            dateOf = { (it as SyncHttpMediaIo).date },
            policy = policy,
        ) ?: io
    }

    /**
     * Refuses a request for [asked] that was answered from [answered], when [policy] does not let
     * a request of the manifest at [manifest] be redirected there. A synchronous request follows a
     * redirect by itself, so this is known only after the answer, and it stops the answer being used.
     */
    private fun requireRedirect(policy: DashUrlPolicy, manifest: String, asked: String, answered: String) {
        DashDoor.requireRedirectAllowed(
            policy = policy,
            shown = MediaItem(asked).label,
            manifestScheme = schemeOf(manifest),
            manifestOrigin = originOf(manifest),
            toScheme = schemeOf(answered),
            toOrigin = originOf(answered),
        )
    }

    private fun schemeOf(address: String): String = address.substringBefore(':', missingDelimiterValue = "").lowercase()
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
 * to [SyncWindows.MAX_BYTES] or what the link delivers in time, and starts small again after a seek, so a file read from the start takes
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
    /** Called with the address asked for and the address that answered, when a redirect made them differ. */
    private val redirected: ((asked: String, answered: String) -> Unit)? = null,
    /** Shared with the readers that [openRelated] makes, so a segment's refusal reaches the engine through the item's reader. */
    private val refusals: SyncRefusals = SyncRefusals(),
) : MediaIo {

    private val state: JsAny = xhrState(headers.toJsObject(), timeout.inWholeMilliseconds.toDouble())
    private var position = 0L
    private var nextWindow = SyncWindows.FIRST_BYTES
    private var closed = false

    /** False until the first request was made, which [start] or the first use of the answer does. */
    private var asked = false

    private val windowStart: Long get() = xhrWindowStart(state).toLong()
    private val windowEnd: Long get() = windowStart + xhrWindowLength(state)

    /** The size the server stated, or the length of a whole body sent for a range it ignored. */
    override val size: Long? get() = answered { xhrTotal(state).takeIf { it >= 0.0 }?.toLong() }

    override val seekable: Boolean get() = true

    override val location: String? get() = answered { xhrLocation(state) }

    override val contentType: String? get() = answered { xhrContentType(state) }

    /** The `Date` header of the newest answer, or null when the server or its origin does not show one. */
    val date: String? get() = answered { xhrDate(state) }

    /**
     * The first [count] bytes of the answer, or all of it when it is shorter, with the position
     * left at the start. Only before the first read.
     */
    fun peek(count: Int): ByteArray {
        start()
        check(position == 0L && windowStart == 0L) { "a reader can only peek before its first read" }
        val length = minOf(count.toLong(), windowEnd).toInt()
        val bytes = xhrSlice(state, 0, length)
        return ByteArray(length) { bytes[it].code.toByte() }
    }

    /**
     * Makes the first request, from the position the reader is at, so a failure to reach the
     * address fails here. The resolver calls it at the open, as other readers fail theirs. The web
     * backend stages a source only when it knows the size. A server on another origin may hide
     * `Content-Range` from the page, so a size it did not state is asked for once more, as the
     * `Content-Length` of a `HEAD` request, which every origin may read.
     */
    fun start() {
        if (asked || closed) return
        asked = true
        fetch(position)
        if (xhrTotal(state) < 0.0) xhrHeadLength(state, url)
    }

    private inline fun <T> answered(read: () -> T): T {
        start()
        return read()
    }

    override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
        check(!closed) { "the reader for $url is closed" }
        if (length == 0) return 0
        start()
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
    override suspend fun openRelated(uri: String): MediaIo? = openRelated(uri, eager = true, redirected)

    /**
     * [openRelated], with [rule] told of every redirect of the new reader's requests. With [eager]
     * false the new reader makes no request until something reads its answer.
     */
    fun openRelated(uri: String, eager: Boolean, rule: ((asked: String, answered: String) -> Unit)?): SyncHttpMediaIo? {
        check(!closed) { "the reader for $url is closed" }
        val scheme = uri.substringBefore(':', missingDelimiterValue = "").lowercase()
        if (scheme != "http" && scheme != "https" && scheme != "blob") return null
        val own = originOf(uri) == originOf(url)
        val related = SyncHttpMediaIo(uri, if (own) headers else emptyMap(), timeout, meter, rule, refusals)
        try {
            if (eager) related.start()
        } catch (failure: Throwable) {
            related.close()
            throw failure
        }
        return related
    }

    override fun networkBitsPerSecond(): Long? = meter.bitsPerSecond()

    /**
     * The newest 401 or 403 that this reader or one it opened was answered (#453, #546), as the
     * player's other network reader keeps it, so the engine opens the item again for a fresh
     * address when a signed one expires during playback.
     */
    override fun takeRefusal(): SourceRefusal? = refusals.take()

    override fun close() {
        if (closed) return
        closed = true
        xhrRelease(state)
    }

    private fun fetch(from: Long) {
        val rate = meter.bitsPerSecond()
        nextWindow = SyncWindows.next(nextWindow, forward = from == windowEnd && from > 0, rate, timeout)
        val allowed = SyncWindows.timeout(nextWindow, rate, timeout)
        val asked = TimeSource.Monotonic.markNow()
        when (val status = xhrFetch(state, url, from.toDouble(), nextWindow.toDouble(), allowed.inWholeMilliseconds.toDouble())) {
            200, 206 -> {
                // The whole request waits for the network, so all of its time counts.
                meter.add(xhrWindowLength(state), asked.elapsedNow())
                val answered = xhrLocation(state)
                if (redirected != null && answered != null && !sameAddress(url, answered)) {
                    try {
                        redirected(url, answered)
                    } catch (refused: Throwable) {
                        // The bytes of a refused answer are never read.
                        xhrRelease(state)
                        throw refused
                    }
                }
            }
            416 -> Unit
            401, 403 -> {
                // A refusal a fresh address may cure, as a signed address that expired is refused.
                refusals.latest = SourceRefusal(url, status)
                throw PlaybackException(PlaybackError.SourceUnavailable(url, null, "HTTP $status"))
            }
            TIMED_OUT -> throw PlaybackException(PlaybackError.SourceStalled(url, timeout))
            CHANGED -> throw PlaybackException(
                PlaybackError.SourceUnavailable(url, null, "the file changed since it was opened: ${xhrError(state)}"),
            )
            MISALIGNED -> throw PlaybackException(
                PlaybackError.SourceUnavailable(url, null, xhrError(state) ?: "a range answer started at another byte"),
            )
            0 -> throw PlaybackException(PlaybackError.SourceUnavailable(url, null, xhrError(state) ?: "the request failed"))
            else -> throw PlaybackException(PlaybackError.SourceUnavailable(url, null, "HTTP $status"))
        }
    }

    private companion object {
        /** What [xhrFetch] answers when the request ran out of time. */
        const val TIMED_OUT = -1

        /** What [xhrFetch] answers when the file is not the one the first answer was. */
        const val CHANGED = -2

        /** What [xhrFetch] answers when a range answer starts at another byte than the one asked for. */
        const val MISALIGNED = -3
    }
}

/**
 * The size and the time limit of each request of a [SyncHttpMediaIo].
 *
 * A browser's request timeout limits the whole request, from send to the last byte, so a window
 * that the link cannot deliver within it failed as a stall while bytes still arrived: a 16 MiB
 * window needs 4.5 Mbit/s to finish in 30 s (#896). Once the reader has measured the link, a
 * window holds no more than the link delivers in [FIT_SHARE] of the stall timeout, and each
 * request is given the stall timeout plus twice the time its window needs at that rate.
 */
internal object SyncWindows {
    const val FIRST_BYTES: Long = 1L shl 20
    const val MAX_BYTES: Long = 16L shl 20

    /** The smallest window a slow link is cut to, so each request still carries some bytes. */
    const val SMALLEST_BYTES: Long = 64L shl 10

    /** How much of the stall timeout a window may take at the measured rate. */
    const val FIT_SHARE: Double = 0.5

    /**
     * The window after [previous]: twice it while the reads run [forward], up to [MAX_BYTES], or
     * [FIRST_BYTES] again after a seek, and never more than [bitsPerSecond] delivers in
     * [FIT_SHARE] of [stall].
     */
    fun next(previous: Long, forward: Boolean, bitsPerSecond: Long?, stall: Duration): Long {
        val grown = if (forward) (previous * 2).coerceAtMost(MAX_BYTES) else FIRST_BYTES
        val rate = bitsPerSecond?.takeIf { it > 0 } ?: return grown
        val fits = (rate / 8.0 * stall.inWholeMilliseconds / 1000.0 * FIT_SHARE).toLong()
        return grown.coerceAtMost(fits.coerceAtLeast(SMALLEST_BYTES))
    }

    /** The time one request of [window] bytes is given: [stall], and twice what the window takes at [bitsPerSecond]. */
    fun timeout(window: Long, bitsPerSecond: Long?, stall: Duration): Duration {
        val rate = bitsPerSecond?.takeIf { it > 0 } ?: return stall
        return stall + (window * 8.0 / rate * 2).seconds
    }
}

/**
 * The newest refusal of a reader and the readers it opened, until it is taken. A worker has one
 * thread, so nothing here is shared between two.
 */
internal class SyncRefusals {
    var latest: SourceRefusal? = null

    fun take(): SourceRefusal? = latest.also { latest = null }
}

/** The scheme, host and port of [address], which a browser resolves against the worker's own. */
@JsFun("(address) => { try { return new URL(address, self.location.href).origin; } catch (e) { return address; } }")
private external fun originOf(address: String): String

/** True when [asked] and [answered] are one address once a browser resolves both, fragments apart. */
@JsFun(
    """(asked, answered) => {
      try {
        const a = new URL(asked, self.location.href); const b = new URL(answered, self.location.href);
        a.hash = ''; b.hash = '';
        return a.href === b.href;
      } catch (e) { return asked === answered; }
    }""",
)
private external fun sameAddress(asked: String, answered: String): Boolean

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
      url: null, type: null, error: null, last: null, tag: null, modified: null,
    })""",
)
private external fun xhrState(headers: JsAny, timeoutMillis: Double): JsAny

/**
 * One synchronous request for [count] bytes from [from]. Answers the status, 0 when the request
 * did not complete, -1 when it ran out of time, -2 when the answer is of another version of the
 * file than the first answer was, and -3 when a range answer starts at another byte than [from]
 * (#855). On 200 or 206 of the same file the answer becomes the window.
 *
 * The first answer's strong entity tag, or else its `Last-Modified`, and its size are the file's
 * identity, as the player's other network reader keeps them (#283). Every later request carries
 * `If-Range` with that validator, so a server whose file changed sends the whole new file, which
 * the validator or the size then tells apart. Headers are read from `getAllResponseHeaders`, which
 * lists only what the origin shows, because asking another origin for a hidden one logs a refusal.
 */
@JsFun(
    """(s, url, from, count, timeout) => {
      const xhr = new XMLHttpRequest();
      xhr.open('GET', url, false);
      xhr.responseType = 'arraybuffer';
      xhr.timeout = timeout;
      for (const k of Object.keys(s.headers)) { try { xhr.setRequestHeader(k, s.headers[k]); } catch (e) {} }
      xhr.setRequestHeader('Range', 'bytes=' + from + '-' + (from + count - 1));
      const validator = s.tag || s.modified;
      if (validator) { try { xhr.setRequestHeader('If-Range', validator); } catch (e) {} }
      try { xhr.send(); } catch (e) {
        s.error = (e && e.message) ? e.message : String(e);
        return (e && e.name === 'TimeoutError') ? -1 : 0;
      }
      const status = xhr.status;
      if (status === 200 || status === 206) {
        const shown = {};
        for (const line of (xhr.getAllResponseHeaders() || '').split(/\r?\n/)) {
          const at = line.indexOf(':');
          if (at > 0) shown[line.slice(0, at).trim().toLowerCase()] = line.slice(at + 1).trim();
        }
        const etag = shown['etag'] && !/^W\//.test(shown['etag']) ? shown['etag'] : null;
        const modified = shown['last-modified'] || null;
        const body = new Uint8Array(xhr.response || new ArrayBuffer(0));
        let start = 0, total = -1;
        if (status === 200) {
          total = body.length;
        } else {
          const range = shown['content-range'] || null;
          const first = range ? /bytes\s+(\d+)-/.exec(range) : null;
          const stated = range ? /\/(\d+)\s*$/.exec(range) : null;
          start = first ? Number(first[1]) : from;
          if (start !== from) {
            s.error = 'the server answered from byte ' + start + ' to a read at byte ' + from;
            return -3;
          }
          if (stated) total = Number(stated[1]);
        }
        if (s.last !== null) {
          if (s.tag !== null && etag !== s.tag) {
            s.error = 'its entity tag was ' + s.tag + ' and is ' + etag;
            return -2;
          }
          if (s.tag === null && s.modified !== null && modified !== null && modified !== s.modified) {
            s.error = 'it was last modified ' + s.modified + ' and is now ' + modified;
            return -2;
          }
          if (s.total >= 0 && total >= 0 && total !== s.total) {
            s.error = 'it had ' + s.total + ' bytes and has ' + total;
            return -2;
          }
        } else {
          s.tag = etag;
          s.modified = etag ? null : modified;
        }
        s.buf = body;
        s.url = xhr.responseURL || url;
        s.type = xhr.getResponseHeader('Content-Type');
        s.last = xhr;
        s.start = start;
        if (total >= 0) s.total = total;
        // Fewer bytes than were asked for, with no stated size: the file ends there.
        else if (s.buf.length < count) s.total = s.start + s.buf.length;
      } else if (status === 416) {
        s.buf = new Uint8Array(0);
        s.start = from;
      }
      return status;
    }""",
)
private external fun xhrFetch(state: JsAny, url: String, from: Double, count: Double, timeoutMillis: Double): Int

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

// Asked of the answer only when a live DASH clock wants it: a browser logs a refusal for each
// header that another origin does not show.
@JsFun("(s) => { try { return s.last ? s.last.getResponseHeader('Date') : null; } catch (e) { return null; } }")
private external fun xhrDate(state: JsAny): String?

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

@JsFun("(s) => { s.buf = new Uint8Array(0); s.last = null; }")
private external fun xhrRelease(state: JsAny)
