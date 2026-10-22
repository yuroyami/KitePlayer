@file:OptIn(ExperimentalAtomicApi::class)

package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.SegmentStore
import io.github.yuroyami.kiteplayer.SegmentStoreEntry
import io.github.yuroyami.kiteplayer.SegmentStoreWriter
import io.ktor.http.HttpHeaders
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * What one open of an item shares about its [SegmentStore] (#547): the store, the addresses that
 * the item's playlists named as segments, and whether the store failed. The item's reader and
 * every reader it opens hold the same one.
 *
 * Only a named address is read from the store or written to it. A playlist, a key and an address
 * that no playlist named always go to the network.
 */
internal class SegmentReuse(val store: SegmentStore, val nowSeconds: () -> Long = ::wallClockSeconds) {
    private val lock = StoreLock()
    private val segments = HashSet<String>()
    private val keys = HashSet<String>()
    private val broken = AtomicBoolean(false)

    /** False once the store failed. The open then reads only from the network. */
    val usable: Boolean get() = !broken.load()

    /** Notes that a finished playlist named [address] as a media or initialization segment. */
    fun nameSegment(address: String) {
        lock.withLock { if (segments.size < MAX_NAMED) segments += address }
    }

    /** Notes that a playlist named [address] as a key, which is then never stored whatever else names it. */
    fun nameKey(address: String) {
        lock.withLock { if (keys.size < MAX_NAMED) keys += address }
    }

    /** True when [address] may be stored: a finished playlist named it as a segment, and none as a key. */
    fun isSegment(address: String): Boolean = lock.withLock { address in segments && address !in keys }

    /** True when a playlist named [address] as a key. */
    fun isKey(address: String): Boolean = lock.withLock { address in keys }

    /** Stops using the store for this open, and reports [failure] through [sink] the first time. */
    fun failed(failure: Throwable, sink: (PlaybackWarning) -> Unit) {
        if (broken.compareAndSet(expectedValue = false, newValue = true)) {
            sink(PlaybackWarning.SegmentStoreFailed(failure.message ?: failure.toString()))
        }
    }

    /**
     * What the store holds for [uri] asked with [headers], or null when this request does not use
     * the store: it carries a login or a cookie and the store is not private to one account, or
     * the store failed.
     */
    fun plan(uri: String, headers: Map<String, String>, sink: (PlaybackWarning) -> Unit): StorePlan? {
        if (!usable) return null
        if (carriesCredentials(headers, uri) && !store.privateToOneAccount) return null
        return try {
            val vary = store.open(varyName(uri)).use { pointer -> pointer.record?.let(::decodeVary) }.orEmpty()
            val name = entryName(uri, headers, vary)
            val entry = store.open(name)
            val record = try {
                entry.record?.let(StoredResponse::decode)
            } catch (failure: Throwable) {
                entry.close()
                throw failure
            }
            StorePlan(this, uri, headers, name, entry, record, sink)
        } catch (failure: Exception) {
            failed(failure, sink)
            null
        }
    }

    /** The name of the entry that lists which request headers the responses of [uri] vary by. */
    fun varyName(uri: String): String = digest("vary", uri, emptyList())

    /**
     * The name of the entry for [uri]: the store's namespace, the address, the values of the
     * request headers named in [vary], and the login and cookie the request states. A digest, so
     * the store never holds an address or a credential in a name.
     */
    fun entryName(uri: String, headers: Map<String, String>, vary: List<String>): String {
        val values = ArrayList<String>()
        for (name in vary) values += "$name=${headerValue(headers, name).orEmpty()}"
        for (name in CREDENTIAL_HEADERS) headerValue(headers, name)?.let { values += "$name:$it" }
        return digest("entry", uri, values)
    }

    private fun digest(kind: String, uri: String, values: List<String>): String =
        sha256Hex((listOf(kind, store.namespace, uri) + values).joinToString("\u0000").encodeToByteArray())

    companion object {
        /** The most addresses one open remembers. A presentation with more keeps the first ones only. */
        private const val MAX_NAMED = 200_000

        private const val VARY_HEADER = "kite-vary 1"

        val CREDENTIAL_HEADERS = listOf(HttpHeaders.Authorization.lowercase(), HttpHeaders.Cookie.lowercase())

        fun headerValue(headers: Map<String, String>, name: String): String? =
            headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

        /** True when a request to [uri] with [headers] names an account: a login, a cookie, or a user in the address. */
        fun carriesCredentials(headers: Map<String, String>, uri: String): Boolean =
            CREDENTIAL_HEADERS.any { headerValue(headers, it) != null } || basicLogin(uri) != null

        fun encodeVary(names: List<String>): ByteArray = "$VARY_HEADER\n${names.joinToString(",")}".encodeToByteArray()

        fun decodeVary(record: ByteArray): List<String>? {
            val lines = record.decodeToString().split('\n')
            if (lines.firstOrNull() != VARY_HEADER) return null
            return lines.getOrNull(1)?.split(',')?.filter { it.isNotEmpty() }
        }
    }
}

/**
 * One request's dealings with the store, from the lookup before the request to the reader that
 * serves and fills the entry afterwards.
 */
internal class StorePlan(
    private val reuse: SegmentReuse,
    private val uri: String,
    private val headers: Map<String, String>,
    private var name: String,
    private var entry: SegmentStoreEntry?,
    record: StoredResponse?,
    private val sink: (PlaybackWarning) -> Unit,
) {
    /**
     * The stored record a reader can be made from, or null. A resource whose server gave no ranges
     * is of use only whole, because a reader could not ask for the bytes that are missing.
     */
    var record: StoredResponse? = record?.takeIf { it.ranged || holdsAll(it.size) }
        private set

    private fun holdsAll(size: Long): Boolean = guarded(false) {
        if (size == 0L) return@guarded true
        var end = 0L
        for (span in entry?.spans().orEmpty()) {
            if (span.first > end) break
            end = maxOf(end, span.last + 1)
        }
        end >= size
    }

    /** Set when the open that made this plan gave it up, which may be while its request still answers. */
    private val abandoned = AtomicBoolean(false)

    /** Gives the plan up: the entry is let go, and an answer that comes later stores nothing. */
    fun abandon() {
        abandoned.store(true)
        release()
    }

    /** The time of day the store's freshness counts by. */
    fun nowSeconds(): Long = reuse.nowSeconds()

    /** The record, when it is fresh now and so needs no request. */
    fun fresh(): StoredResponse? = record?.takeIf { it.isFresh(reuse.nowSeconds()) }

    /** The request header that asks the server whether the stored resource changed, or null. */
    fun condition(): Pair<String, String>? = record?.condition()

    /** The server answered 304 to [condition]: the spans stay, and the record is fresh again. */
    fun revalidated(facts: ResponseFacts, requestSeconds: Long): StoredResponse? {
        val renewed = record?.revalidated(facts, requestSeconds, reuse.nowSeconds()) ?: return null
        guarded(Unit) { entry?.putRecord(renewed.encode()) }
        record = renewed
        return renewed
    }

    /**
     * The server answered with the resource itself, described by [fresh], or by null when the
     * answer must not be stored. Everything stored before goes first, unless the answer is of the
     * same file by its strong entity tag and size, so old and new bytes never mix.
     */
    fun answered(fresh: StoredResponse?, vary: List<String>?, sentCredentials: Boolean, sentVaried: Boolean) {
        guarded(Unit) {
            val old = record
            val same = old != null && fresh != null && old.entityTag != null &&
                old.entityTag == fresh.entityTag && old.size == fresh.size && old.ranged == fresh.ranged
            val storable = fresh != null && vary != null && !sentVaried && reuse.usable && !abandoned.load() &&
                (!sentCredentials || reuse.store.privateToOneAccount)
            if (entry?.record != null && (!same || !storable)) drop()
            if (!storable) {
                release()
                return@guarded
            }
            // The name holds the values of the headers the server varies by, which only its answer lists.
            val pointer = reuse.varyName(uri)
            val known = reuse.store.open(pointer).use { it.record?.let(SegmentReuse::decodeVary) }.orEmpty()
            if (known != vary) {
                if (vary.isEmpty()) {
                    reuse.store.remove(pointer)
                } else {
                    reuse.store.open(pointer).use { it.putRecord(SegmentReuse.encodeVary(vary)) }
                }
                val renamed = reuse.entryName(uri, headers, vary)
                if (renamed != name) {
                    release()
                    name = renamed
                    reuse.store.remove(renamed)
                }
            }
            val into = entry ?: reuse.store.open(name).also { entry = it }
            into.putRecord(fresh.encode())
            record = fresh
        }
    }

    /** The server refused the request for good, so nothing stored for it stays. */
    fun refused() {
        guarded(Unit) {
            if (entry?.record != null) drop()
            release()
        }
    }

    /** Removes the entry with every span, and holds a new, empty one of the same name. */
    private fun drop() {
        entry?.close()
        entry = null
        reuse.store.remove(name)
        record = null
        entry = reuse.store.open(name)
    }

    /** The reader's side of the entry, or null when this request stores nothing. The plan is spent after it. */
    fun spans(): StoredSpans? {
        val held = entry ?: return null
        if (record == null || !reuse.usable || abandoned.load()) {
            release()
            return null
        }
        entry = null
        return StoredSpans(reuse, name, held, sink)
    }

    /** Lets go of the entry. Idempotent. */
    fun release() {
        runCatching { entry?.close() }
        entry = null
        record = null
    }

    private inline fun <T> guarded(otherwise: T, block: () -> T): T = try {
        block()
    } catch (failure: Exception) {
        release()
        reuse.failed(failure, sink)
        otherwise
    }
}

/**
 * The entry of one reader: it gives the reader the bytes that are stored, and takes the bytes the
 * reader got from the network as new spans. A span is published when the run of bytes ends: at a
 * seek, at the end of the resource and at the close. A failure of the store ends its use for the
 * open, and the reader goes on from the network.
 */
internal class StoredSpans(
    private val reuse: SegmentReuse,
    private val name: String,
    entry: SegmentStoreEntry,
    private val sink: (PlaybackWarning) -> Unit,
) {
    private var entry: SegmentStoreEntry? = entry
    private var writer: SegmentStoreWriter? = null
    private var writerEnd = 0L

    /** Reads stored bytes at [position], or returns 0 when the store has none there. */
    fun read(position: Long, into: ByteArray, offset: Int, length: Int): Int = guarded(0) {
        entry?.read(position, into, offset, length) ?: 0
    }

    /** Takes [length] bytes that the network delivered at [position]. */
    fun wrote(position: Long, from: ByteArray, offset: Int, length: Int) {
        guarded(Unit) {
            val held = entry ?: return@guarded
            if (writer != null && writerEnd != position) publish()
            val open = writer ?: held.write(position).also {
                writer = it
                writerEnd = position
            }
            open.write(from, offset, length)
            writerEnd += length
        }
    }

    /** Publishes the bytes taken so far as one span. */
    fun publish() {
        val open = writer ?: return
        writer = null
        guarded(Unit) { open.use { it.publish() } }
    }

    /** The resource changed on the server, so everything stored for it goes and nothing more is taken. */
    fun changed() {
        guarded(Unit) {
            writer?.close()
            writer = null
            entry?.close()
            entry = null
            reuse.store.remove(name)
        }
    }

    fun close() {
        publish()
        runCatching { entry?.close() }
        entry = null
    }

    private inline fun <T> guarded(otherwise: T, block: () -> T): T = try {
        block()
    } catch (failure: Exception) {
        runCatching { writer?.close() }
        writer = null
        runCatching { entry?.close() }
        entry = null
        reuse.failed(failure, sink)
        otherwise
    }
}

/**
 * Reads the bytes of one reader as they pass, and when they are an HLS playlist, tells [reuse]
 * which addresses it names (#547). The segments and initializations of a playlist count only once
 * its `EXT-X-ENDLIST` was read, so a live playlist names none. A key counts at once.
 *
 * Each address is resolved against every one of [bases], the address that was asked for and the
 * one that answered, because either may be what the backend resolves against.
 */
internal class PlaylistNames(private val reuse: SegmentReuse, private val bases: List<String>) {
    private var expected = 0L
    private var done = false
    private var first = true
    private var ended = false
    private val line = StringBuilder()
    private val pending = ArrayList<String>()

    /** Takes [length] bytes read at [position]. Bytes out of order end the reading. */
    fun feed(position: Long, from: ByteArray, offset: Int, length: Int) {
        if (done) return
        if (position != expected) {
            done = true
            return
        }
        expected += length
        for (index in offset until offset + length) {
            val byte = from[index]
            if (byte == NEWLINE) {
                takeLine()
                if (done) return
            } else {
                if (line.length >= MAX_LINE) {
                    // Media, or a line no playlist has.
                    done = true
                    return
                }
                // An address outside ASCII is percent-encoded in a playlist, so a byte is a character here.
                line.append((byte.toInt() and 0xFF).toChar())
            }
        }
    }

    /** The bytes ended, so a last line with no line break is read too. */
    fun end() {
        if (done) return
        takeLine()
        done = true
    }

    private fun takeLine() {
        val text = line.toString().trim().removePrefix(BOM)
        line.clear()
        if (first) {
            first = false
            if (!text.startsWith("#EXTM3U")) {
                done = true
                return
            }
        }
        when {
            text.isEmpty() -> Unit
            !text.startsWith("#") -> segment(text)
            text.startsWith("#EXT-X-ENDLIST") -> {
                ended = true
                for (address in pending) reuse.nameSegment(address)
                pending.clear()
            }
            text.startsWith("#EXT-X-MAP:") -> attributeUri(text)?.let(::segment)
            text.startsWith("#EXT-X-KEY:") || text.startsWith("#EXT-X-SESSION-KEY:") ->
                attributeUri(text)?.let { each(it, reuse::nameKey) }
        }
    }

    private fun segment(reference: String) {
        each(reference) { address ->
            if (ended) reuse.nameSegment(address) else if (pending.size < MAX_PENDING) pending += address
        }
    }

    private inline fun each(reference: String, take: (String) -> Unit) {
        // A variable is filled in by the backend, so the address it makes is unknown here.
        if ("{$" in reference) return
        for (base in bases) for (address in resolveReference(base, reference)) take(address)
    }

    /** The value of the `URI` attribute of the tag [text], or null when it has none. */
    private fun attributeUri(text: String): String? {
        var from = text.indexOf(':')
        while (true) {
            val at = text.indexOf("URI=\"", from)
            if (at < 0) return null
            val before = text[at - 1]
            if (before == ':' || before == ',' || before == ' ') {
                val end = text.indexOf('"', at + 5)
                return if (end < 0) null else text.substring(at + 5, end)
            }
            from = at + 5
        }
    }

    private companion object {
        const val NEWLINE: Byte = 10
        const val MAX_LINE = 8_192
        const val MAX_PENDING = 200_000
        const val BOM = "ï»¿"
    }
}

/**
 * The absolute addresses that [reference] may mean in a document at [base], as RFC 3986, 5.2
 * resolves it. The first has its `.` and `..` levels taken out, and the second, when it differs,
 * keeps them, because not every resolver removes them from an address that is already absolute.
 */
internal fun resolveReference(base: String, reference: String): List<String> {
    val ref = reference.substringBefore('#')
    if (ref.isEmpty()) return emptyList()
    val raw = when {
        hasScheme(ref) -> ref
        else -> {
            val bare = base.substringBefore('#').substringBefore('?')
            val schemeEnd = bare.indexOf("://")
            if (schemeEnd < 0) return emptyList()
            val pathStart = bare.indexOf('/', schemeEnd + 3).let { if (it < 0) bare.length else it }
            val origin = bare.substring(0, pathStart)
            val path = bare.substring(pathStart).ifEmpty { "/" }
            when {
                ref.startsWith("//") -> bare.substring(0, schemeEnd + 1) + ref
                ref.startsWith("/") -> origin + ref
                ref.startsWith("?") -> origin + path + ref
                else -> origin + path.substringBeforeLast('/') + "/" + ref
            }
        }
    }
    val clean = withoutDotLevels(raw)
    return if (clean == raw) listOf(raw) else listOf(clean, raw)
}

private fun hasScheme(reference: String): Boolean {
    val colon = reference.indexOf(':')
    if (colon <= 0) return false
    return reference[0].isLetter() && reference.substring(0, colon).all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }
}

/** [address] with the `.` and `..` levels of its path resolved (RFC 3986, 5.2.4). The query is left as it is. */
private fun withoutDotLevels(address: String): String {
    val schemeEnd = address.indexOf("://")
    if (schemeEnd < 0) return address
    val pathStart = address.indexOf('/', schemeEnd + 3)
    if (pathStart < 0) return address
    val queryStart = address.indexOf('?', pathStart).let { if (it < 0) address.length else it }
    val levels = ArrayList<String>()
    val parts = address.substring(pathStart + 1, queryStart).split('/')
    for ((index, part) in parts.withIndex()) {
        val last = index == parts.lastIndex
        when (part) {
            "." -> if (last) levels += ""
            ".." -> {
                if (levels.isNotEmpty()) levels.removeAt(levels.lastIndex)
                if (last) levels += ""
            }
            else -> levels += part
        }
    }
    return address.substring(0, pathStart) + "/" + levels.joinToString("/") + address.substring(queryStart)
}
