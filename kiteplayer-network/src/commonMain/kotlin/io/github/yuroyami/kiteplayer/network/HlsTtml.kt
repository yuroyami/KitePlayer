package io.github.yuroyami.kiteplayer.network

import io.github.yuroyami.kiteplayer.KiteLog
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.Playlists
import io.github.yuroyami.kiteplayer.network.dash.DashSubtitles
import io.github.yuroyami.kiteplayer.network.dash.Ttml
import io.github.yuroyami.kiteplayer.network.dash.readAllBounded
import io.github.yuroyami.kiteplayer.network.dash.webVtt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * TTML subtitles in HLS (#439). FFmpeg's HLS reader opens every subtitle rendition with its WebVTT
 * reader, whatever the segments hold, so an IMSC rendition, TTML in fragmented MP4 (`stpp`), shows
 * nothing. This reader stands in front of an HLS master playlist whose variants name `stpp` among
 * their codecs, as RFC 8216 asks of a stream with IMSC subtitles, and serves each such rendition as
 * WebVTT, through the converter the DASH path uses (#402), so the text, the line breaks, italic,
 * bold and underline come out the same.
 *
 * The rendition's playlist reaches FFmpeg with each segment named under this reader's own host and
 * its initialization and byte ranges gone, which a WebVTT reader cannot take; every other tag, the
 * durations and the live window included, stays. Each segment is then read with its range and its
 * initialization, and served as WebVTT at the times of its own track, which is the timeline the
 * picture of a fragmented MP4 stream is on. A segment of TTML text, or one that is already WebVTT,
 * is served as well. Everything else, and every playlist that is not such a rendition, opens
 * through [upstream] as it would without this reader.
 */
internal class HlsTtmlMediaIo(
    private val upstream: MediaIo,
    /** The addresses of the renditions to serve as WebVTT, resolved against the master's. */
    private val renditions: Set<String>,
) : MediaIo by upstream {

    private val lock = Mutex()

    /** The segments named under this reader's host, by the address they are named under. */
    private val segments = LinkedHashMap<String, Segment>()

    /** The address each segment of a rendition is named under, so a reload names it the same. */
    private val addresses = HashMap<Segment, String>()

    /** The initializations read, by address and range. */
    private val inits = HashMap<String, ByteArray>()

    private data class Segment(val url: String, val range: LongRange?, val initUrl: String?, val initRange: LongRange?)

    override suspend fun openRelated(uri: String): MediaIo? {
        if (uri in renditions) return playlist(uri)
        lock.withLock { segments[uri] }?.let { segment -> return MemoryReader(cuesOf(segment).encodeToByteArray(), uri, WEBVTT_TYPE) }
        if (uri.substringAfter("://").substringBefore('/').equals(HOST, ignoreCase = true)) return null
        return upstream.openRelated(uri)
    }

    /** The rendition's playlist at [uri], its segments named under this reader's host. */
    private suspend fun playlist(uri: String): MediaIo {
        val io = upstream.openRelated(uri) ?: throw KtorMediaIoException("${shownUri(uri)} could not be opened")
        val text = try {
            readAllBounded(io, MAX_PLAYLIST_BYTES, "the subtitle playlist at ${shownUri(uri)}").decodeToString()
        } finally {
            io.close()
        }
        val base = io.location ?: uri
        val out = StringBuilder()
        var init: Pair<String, LongRange?>? = null
        // The next segment's byte range: its length, and its offset when the tag states one.
        var range: Pair<Long, Long?>? = null
        // Where the next byte range of each file starts when the tag leaves its offset out.
        val nextOffset = HashMap<String, Long>()
        lock.withLock {
            for (raw in text.removePrefix("﻿").split('\n')) {
                val line = raw.removeSuffix("\r")
                when {
                    line.startsWith("#EXT-X-MAP:") -> {
                        val attributes = hlsAttributes(line.substringAfter(':'))
                        init = attributes["URI"]?.let { reference ->
                            // An initialization's range with no offset starts at the file's first byte.
                            Playlists.resolve(base, reference) to attributes["BYTERANGE"]?.let(::byteRange)?.let { (length, offset) ->
                                (offset ?: 0L) until (offset ?: 0L) + length
                            }
                        }
                    }
                    line.startsWith("#EXT-X-BYTERANGE:") -> range = byteRange(line.substringAfter(':'))
                    line.isNotBlank() && !line.startsWith("#") -> {
                        val url = Playlists.resolve(base, line.trim())
                        // A range with no offset follows the one before it in the same file.
                        val wanted = range?.let { (length, offset) ->
                            val start = offset ?: nextOffset[url] ?: 0L
                            start until start + length
                        }
                        wanted?.let { nextOffset[url] = it.last + 1 }
                        val segment = Segment(url, wanted, init?.first, init?.second)
                        val address = addresses.getOrPut(segment) { "https://$HOST/${addresses.size}.vtt" }
                        segments[address] = segment
                        out.append(address).append('\n')
                        range = null
                    }
                    else -> out.append(line).append('\n')
                }
            }
            while (segments.size > MAX_SEGMENTS) {
                val oldest = segments.keys.first()
                addresses.remove(segments.remove(oldest))
            }
        }
        return MemoryReader(out.toString().encodeToByteArray(), base, HLS_TYPE)
    }

    /** [segment] as WebVTT. */
    private suspend fun cuesOf(segment: Segment): String {
        val bytes = read(segment.url, segment.range, MAX_SEGMENT_BYTES)
        if (looksLikeWebVtt(bytes)) return bytes.decodeToString()
        val cues = if (looksLikeMp4(bytes)) {
            val initUrl = segment.initUrl ?: throw KtorMediaIoException("the MP4 subtitle segment ${shownUri(segment.url)} names no initialization")
            DashSubtitles.mp4Cues(initOf(initUrl, segment.initRange), bytes)
        } else {
            Ttml.cues(bytes.decodeToString())
        }
        return webVtt(cues)
    }

    private suspend fun initOf(url: String, range: LongRange?): ByteArray {
        val key = "$url#$range"
        lock.withLock { inits[key] }?.let { return it }
        val bytes = read(url, range, MAX_SEGMENT_BYTES)
        lock.withLock {
            if (inits.size >= MAX_INITS) inits.clear()
            inits[key] = bytes
        }
        return bytes
    }

    /** The bytes of [url], or of its [range] of it, at most [limit] of them. */
    private suspend fun read(url: String, range: LongRange?, limit: Long): ByteArray {
        val io = upstream.openRelated(url) ?: throw KtorMediaIoException("${shownUri(url)} could not be opened")
        try {
            if (range == null) return readAllBounded(io, limit, "the subtitle segment at ${shownUri(url)}")
            val wanted = range.last - range.first + 1
            if (wanted > limit) throw KtorMediaIoException("${shownUri(url)} asks for $wanted bytes, and the ceiling is $limit")
            if (range.first > 0) io.seek(range.first)
            val out = ByteArray(wanted.toInt())
            var filled = 0
            while (filled < out.size) {
                val count = io.read(out, filled, out.size - filled)
                if (count < 0) break
                filled += count
            }
            return if (filled == out.size) out else out.copyOf(filled)
        } finally {
            io.close()
        }
    }

    /** A playlist or a segment served from memory, at [location]. */
    private class MemoryReader(private val bytes: ByteArray, override val location: String, override val contentType: String) : MediaIo {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        override suspend fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override suspend fun seek(position: Long) {
            require(position in 0L..size) { "seek to $position outside 0..$size" }
            this.position = position.toInt()
        }

        override fun setWarningSink(sink: (PlaybackWarning) -> Unit) {}

        override fun close() {}
    }

    internal companion object {
        /** The host this reader names converted segments under, which no network has. */
        const val HOST: String = "kite-hls-subtitles.invalid"

        private const val HLS_TYPE = "application/vnd.apple.mpegurl"
        private const val WEBVTT_TYPE = "text/vtt"
        private const val MAX_PLAYLIST_BYTES = 4L * 1024 * 1024
        private const val MAX_SEGMENT_BYTES = 4L * 1024 * 1024
        private const val MAX_SEGMENTS = 8192
        private const val MAX_INITS = 64

        /** The most bytes of a master playlist read to decide, which any master fits. */
        const val MAX_MASTER_BYTES: Int = 512 * 1024

        /**
         * The reader for [io] when it answers with an HLS master playlist whose variants name
         * `stpp`, or null, when [io] then reads on from its first byte as before. Only a response
         * that says it is HLS, by its type or its address, is looked at.
         */
        suspend fun readerIfTtml(io: KtorMediaIo): MediaIo? {
            if (!declaredHls(io.contentType, io.location)) return null
            val head = io.peek(MAX_MASTER_BYTES + 1)
            if (head.size > MAX_MASTER_BYTES) return null
            val renditions = ttmlRenditions(head.decodeToString(), io.location)
            if (renditions.isEmpty()) return null
            KiteLog.log("KiteHls", "${renditions.size} TTML subtitle rendition(s) of ${shownUri(io.location)} are served as WebVTT")
            return HlsTtmlMediaIo(io, renditions)
        }

        /**
         * The addresses of the subtitle renditions of [master] that are TTML, resolved against
         * [base]: those of every group that a variant naming `stpp` among its codecs names.
         */
        fun ttmlRenditions(master: String, base: String): Set<String> {
            val lines = master.removePrefix("﻿").split('\n').map { it.removeSuffix("\r") }
            val groups = lines.filter { it.startsWith("#EXT-X-STREAM-INF:") }
                .map { hlsAttributes(it.substringAfter(':')) }
                .filter { variant -> variant["CODECS"].orEmpty().split(',').any { it.trim().lowercase().startsWith("stpp") } }
                .mapNotNull { it["SUBTITLES"] }
                .toSet()
            if (groups.isEmpty()) return emptySet()
            return lines.filter { it.startsWith("#EXT-X-MEDIA:") }
                .map { hlsAttributes(it.substringAfter(':')) }
                .filter { it["TYPE"] == "SUBTITLES" && it["GROUP-ID"] in groups }
                .mapNotNull { rendition -> rendition["URI"]?.let { Playlists.resolve(base, it) } }
                .toSet()
        }

        /** True when a response says it is an HLS playlist, by its type or by an address ending in `.m3u8`. */
        fun declaredHls(contentType: String?, address: String): Boolean {
            val type = contentType?.substringBefore(';')?.trim()?.lowercase()
            if (type == HLS_TYPE || type == "application/x-mpegurl" || type == "audio/mpegurl" || type == "audio/x-mpegurl") return true
            val path = address.substringBefore('#').substringBefore('?').lowercase()
            return path.endsWith(".m3u8")
        }

        private fun looksLikeWebVtt(bytes: ByteArray): Boolean =
            bytes.decodeToString(0, minOf(bytes.size, 16)).removePrefix("﻿").startsWith("WEBVTT")

        /** True when [bytes] start with an MP4 box of a kind a segment starts with. */
        private fun looksLikeMp4(bytes: ByteArray): Boolean {
            if (bytes.size < 8) return false
            val type = bytes.decodeToString(4, 8)
            return type == "styp" || type == "moof" || type == "ftyp" || type == "sidx" || type == "emsg" || type == "prft"
        }

        /** `n[@o]`, as `EXT-X-BYTERANGE` and `EXT-X-MAP` write it: the length, and the offset when one is given. */
        private fun byteRange(spec: String): Pair<Long, Long?>? {
            val length = spec.substringBefore('@').trim().toLongOrNull()?.takeIf { it > 0 } ?: return null
            return length to spec.substringAfter('@', "").trim().toLongOrNull()
        }

        /** An HLS attribute list, RFC 8216, section 4.2, with quotes removed. */
        fun hlsAttributes(list: String): Map<String, String> {
            val attributes = LinkedHashMap<String, String>()
            var at = 0
            while (at < list.length) {
                val equals = list.indexOf('=', at)
                if (equals < 0) break
                val name = list.substring(at, equals).trim()
                val valueStart = equals + 1
                val value: String
                val next: Int
                if (valueStart < list.length && list[valueStart] == '"') {
                    val close = list.indexOf('"', valueStart + 1).let { if (it < 0) list.length else it }
                    value = list.substring(valueStart + 1, close)
                    next = list.indexOf(',', close).let { if (it < 0) list.length else it + 1 }
                } else {
                    val comma = list.indexOf(',', valueStart).let { if (it < 0) list.length else it }
                    value = list.substring(valueStart, comma).trim()
                    next = comma + 1
                }
                if (name.isNotEmpty()) attributes[name] = value
                at = next
            }
            return attributes
        }
    }
}
