package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.FFmpegError
import io.github.yuroyami.kiteffmpeg.FFmpegException
import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.OpenInterrupt
import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One opened item: the KiteFFmpeg source, and the blocking bridge when the item brought its own
 * reader. An interrupt must reach the bridge as well as the source, because FFmpeg's own flag
 * cannot end a read that waits inside the reader. [hls] is set for an HLS stream read through the
 * item's reader.
 */
internal class OpenedItem(
    val source: MediaSource,
    val bridge: BlockingMediaIo?,
    val hls: HlsLedger? = null,
    /** The variants of an HLS master playlist and the index of the one kept, for the track table. */
    val variants: List<io.github.yuroyami.kiteplayer.StreamVariant> = emptyList(),
    val selectedVariant: Int? = null,
    /** True when the URL fallback opened a scheme whose sender pushes media at the pace it plays. */
    val realTimeScheme: Boolean = false,
    /** The title a list of streams gave the stream it opened, for an item that named the list (#450). */
    val listedTitle: String? = null,
) {
    fun withListedTitle(title: String?): OpenedItem =
        OpenedItem(source, bridge, hls, variants, selectedVariant, realTimeScheme, title ?: listedTitle)
}

/**
 * Opens a KiteFFmpeg source for [item]. Playback, thumbnails, waveforms and loudness all open
 * through here, so each gets the same options: [preOpenOptions] builds them from the typed fields
 * and the raw ones, and refuses a collision before any reader exists. The bytes come from the
 * item's own reader when it has one, then from a descriptor the item names when it can be read by
 * position, otherwise from the URL fallback: FFmpeg's own protocols on the URI. The caller closes
 * the source.
 *
 * Cancelling the calling coroutine ends the open, and this then throws a `CancellationException`
 * rather than a media error. The cancel raises an [OpenInterrupt], which FFmpeg sees inside its
 * network protocols and during stream discovery, and it interrupts the bridge of an item's own
 * reader. A wait anywhere else inside FFmpeg finishes before the cancel is seen.
 *
 * An HLS playlist read through the item's reader opens through [openHls]: the playlist is read
 * here, a master playlist keeps one variant, and the reader opens the addresses the playlist names.
 */
internal suspend fun openItem(item: MediaItem, listDepth: Int = 0): OpenedItem {
    FFmpegLogForwarding.install()
    val options = preOpenOptions(item)
    // Called once per open: the reader it makes belongs to this source and is closed with it.
    val opened = item.io?.open()
    // A playlist that nothing marks is recognised by its first bytes (#400). The reader keeps them.
    val io = opened?.let { reader ->
        val address = reader.location ?: item.uri
        val marked = looksLikeHls(item.formatHint, reader.contentType, address) ||
            looksLikePls(item.formatHint, reader.contentType, address)
        if (marked || item.formatHint != null || !mayBeAPlaylist(reader.contentType)) {
            reader
        } else {
            try {
                SniffedMediaIo.sniff(reader, HLS_SNIFF_BYTES)
            } catch (failure: Throwable) {
                reader.close()
                throw failure
            }
        }
    }
    // FFmpeg's fd protocol takes the "fd" key only with this exact URI.
    val descriptor = if (io == null && item.uri == "fd:") options["fd"]?.toIntOrNull()?.let(::descriptorByteSource) else null
    // It stays with the source the open returns, as MediaSource.interrupt() does.
    val cancel = OpenInterrupt()
    // A list of stream addresses is not media: the streams it names are (#450). A PLS file, and an
    // M3U list with no HLS tags, play their first entry that opens.
    var playlistText: String? = null
    if (io != null) {
        val address = io.location ?: item.uri
        val pls = looksLikePls(item.formatHint, io.contentType, address) || (io is SniffedMediaIo && startsLikePls(io.head))
        val m3u = !pls && (looksLikeHls(item.formatHint, io.contentType, address) || (io is SniffedMediaIo && startsLikeHls(io.head)))
        if (pls || m3u) {
            val bytes = try {
                readPlaylist(io, item.uri)
            } catch (failure: Throwable) {
                io.close()
                throw failure
            }
            val text = bytes.decodeToString()
            // The text decides over its label: a PLS file sent as M3U is still a PLS file.
            val entries = when {
                startsLikePls(bytes) -> parsePls(text, address)
                startsLikeHls(bytes) || !pls -> plainStreamList(text, address)
                else -> parsePls(text, address)
            }
            if (entries != null) return openStreamList(item, io, entries, listDepth)
            // An HLS playlist after all: its text is read, and the HLS path takes it as it is.
            playlistText = text
        }
    }
    return when {
        // The custom AVIO bridge: the reader carries the media, with no path and no FFmpeg protocol.
        io != null -> {
            // Every bridge of this source lives on it, the bridges of an HLS stream's segments too.
            val lifetime = Job()
            val playlist = playlistText != null ||
                looksLikeHls(item.formatHint, io.contentType, io.location ?: item.uri) ||
                (io is SniffedMediaIo && startsLikeHls(io.head))
            val hls = if (playlist) {
                try {
                    openHls(item, io, lifetime, playlistText)
                } catch (failure: Throwable) {
                    io.close()
                    throw failure
                }
            } else {
                null
            }
            val bridge = BlockingMediaIo(hls?.playlist ?: io, lifetime)
            val source = openCancellably({ cancel.interrupt(); bridge.interrupt() }) {
                if (hls == null) {
                    MediaSource.open(bridge, options, cancel)
                } else {
                    MediaSource.open(
                        bridge,
                        options + hls.options(item),
                        cancel,
                        url = hls.url,
                        mimeType = HLS_MEDIA_TYPE,
                        nestedOpener = hls.opener,
                    )
                }
            }
            OpenedItem(source, bridge, hls?.ledger, hls?.variants.orEmpty(), hls?.selectedVariant)
        }
        // No protocol reads the descriptor now, so no protocol is left to consume its key.
        descriptor != null ->
            OpenedItem(openCancellably(cancel::interrupt) { MediaSource.open(descriptor, options - "fd", cancel) }, null)
        else -> {
            // Refused before anything is opened, so a scheme with no protocol sends nothing anywhere.
            requireFallbackScheme(item.uri)
            val source = openCancellably(cancel::interrupt) { openUrlFallback(item.uri, item.formatHint, options, cancel) }
            OpenedItem(source, null, realTimeScheme = fallbackScheme(item.uri) in realTimeSchemes || source.formatName == "sdp")
        }
    }
}

/**
 * Runs [open] and calls [interrupt] the moment the calling coroutine is cancelled. The open blocks
 * this thread inside FFmpeg, so the cancellation cannot arrive any other way. A cancelled open ends
 * as a cancellation, never as the media error FFmpeg reports for it.
 */
private suspend inline fun openCancellably(noinline interrupt: () -> Unit, open: () -> MediaSource): MediaSource {
    val source = try {
        interruptedWhenCancelled(interrupt, open)
    } catch (failure: Throwable) {
        currentCoroutineContext().ensureActive()
        throw failure
    }
    if (!currentCoroutineContext().isActive) {
        // FFmpeg finished with what it had read before the cancellation reached it.
        source.close()
        currentCoroutineContext().ensureActive()
    }
    return source
}

private suspend inline fun <T> interruptedWhenCancelled(noinline interrupt: () -> Unit, open: () -> T): T {
    val caller = currentCoroutineContext()[Job] ?: return open()
    // A child job completes as soon as its parent is cancelled, even while this thread is blocked
    // below, and its handler runs on the thread that cancelled. Completing it afterwards detaches
    // it, so the caller's job can finish.
    val link = Job(caller)
    link.invokeOnCompletion { cause -> if (cause != null) interrupt() }
    try {
        return open()
    } finally {
        link.complete()
    }
}

/**
 * Opens the first of [entries] that opens, each as an item of its own through [list]'s related
 * reader, which a list's later entries usually need, since they are backups of the same station
 * (#450). The stream that opens keeps [list] open and carries its entry's title. When none opens,
 * the last failure is thrown. A list that names a list is followed, a few levels deep at most.
 */
private suspend fun openStreamList(item: MediaItem, list: MediaIo, entries: List<StreamEntry>, depth: Int): OpenedItem {
    if (entries.isEmpty() || depth >= MAX_STREAM_LIST_DEPTH) {
        list.close()
        val why = if (entries.isEmpty()) "the playlist names no stream" else "the playlist names playlists $MAX_STREAM_LIST_DEPTH levels deep"
        throw PlaybackException(PlaybackError.NotMedia(item.label, why))
    }
    var last: Throwable? = null
    for (entry in entries) {
        val stream = try {
            list.openRelated(entry.address)
        } catch (failure: Throwable) {
            if (failure is CancellationException) {
                list.close()
                throw failure
            }
            last = failure
            null
        } ?: continue
        val reader = ListedStreamIo(stream)
        try {
            val opened = openItem(item.copy(uri = entry.address, io = { reader }, formatHint = null), depth + 1)
            reader.list = list
            return opened.withListedTitle(entry.title)
        } catch (failure: Throwable) {
            runCatching { reader.close() }
            if (failure is CancellationException) {
                list.close()
                throw failure
            }
            last = failure
        }
    }
    list.close()
    throw last ?: PlaybackException(
        PlaybackError.NotMedia(item.label, "no stream the playlist names could be opened"),
    )
}

private const val MAX_STREAM_LIST_DEPTH = 3

/**
 * Runs [open], one of the player's two doors into [openItem], and types what FFmpeg refused, which
 * the engine would otherwise report as `SourceUnavailable`, saying the bytes could not be reached.
 * The FFmpeg runtime rejection becomes `ConfigurationInvalid`, as [mappingFFmpegRuntimeRejection]
 * says. Invalid data becomes `NotMedia`: the bytes were reached and FFmpeg cannot read them as
 * media, as with a file that is not media, a damaged header, or an HLS playlist that breaks its
 * specification by using a variable it never defined (#452). The same bytes fail the same way every
 * time, so a retry is pointless, and FFmpeg's reason, which names what it refused, is the detail.
 * Thumbnails, waveforms and loudness keep FFmpeg's own exception, as their documentation says.
 */
internal inline fun <T> typingOpenFailures(item: MediaItem, open: () -> T): T = try {
    mappingFFmpegRuntimeRejection(open)
} catch (failure: FFmpegException) {
    if (failure.error !is FFmpegError.InvalidData) throw failure
    throw PlaybackException(PlaybackError.NotMedia(item.uri, failure.message))
}

/** [openItem] for the callers that need only the source: thumbnails, waveforms and loudness. */
internal suspend fun openSource(item: MediaItem): MediaSource = openItem(item).source

/**
 * The URL fallback: FFmpeg's own protocols read the URI, for an item with no reader and no
 * resolver answer. The build's protocols are those [fallbackSchemes] names, so https needs the
 * network module. Every network read waits at most [URL_FALLBACK_READ_TIMEOUT], and FFmpeg limits
 * the connection itself to 5 seconds. The fallback does not reconnect. [cancel] stops the open
 * while it waits on the network.
 */
private fun openUrlFallback(uri: String, formatHint: String?, options: Map<String, String>, cancel: OpenInterrupt): MediaSource =
    // Keys the demuxer did not consume come back from KiteFFmpeg instead of being dropped.
    MediaSource.open(fallbackAddress(uri), urlFallbackOptions(uri, options, formatHint), cancel)

/**
 * The options the URL fallback opens [uri] with: [options], plus what bounds a read on its scheme
 * and what an SDP file needs, each only where the item does not set it in `MediaItem.openOptions`.
 * Every timeout is in microseconds.
 *
 * - http, tcp and rtmp take `rw_timeout`, which limits each read and which FFmpeg copies to the tcp
 *   connection under them.
 * - udp takes its own `timeout`, which FFmpeg's udp protocol puts in place of `rw_timeout`.
 * - rtsp takes the demuxer's `timeout`, without which it waits for a camera for ever.
 * - An rtp address carries its timeout in the address instead, as [fallbackAddress] writes it, and
 *   an SDP file's session gives up after FFmpeg's own 10 seconds.
 * - An SDP file read from disk, by its `.sdp` name or [formatHint], may reach `udp` and `rtp`,
 *   because FFmpeg lets an input opened through `file` reach only `file`, `crypto` and `data`.
 */
internal fun urlFallbackOptions(uri: String, options: Map<String, String>, formatHint: String? = null): Map<String, String> {
    val timeout = URL_FALLBACK_READ_TIMEOUT.inWholeMicroseconds.toString()
    var result = options
    when (fallbackScheme(uri)) {
        "http", "tcp", "rtmp" -> if ("rw_timeout" !in options) result = result + ("rw_timeout" to timeout)
        "udp", "rtsp" -> if ("timeout" !in options) result = result + ("timeout" to timeout)
        "file" -> {
            val sdp = formatHint.equals("sdp", ignoreCase = true) ||
                uri.substringBefore('?').endsWith(".sdp", ignoreCase = true)
            if (sdp && "protocol_whitelist" !in options) result = result + ("protocol_whitelist" to "file,udp,rtp")
        }
    }
    return result
}

/** How long one read of the URL fallback waits for bytes over the network. */
internal val URL_FALLBACK_READ_TIMEOUT: Duration = 10.seconds
