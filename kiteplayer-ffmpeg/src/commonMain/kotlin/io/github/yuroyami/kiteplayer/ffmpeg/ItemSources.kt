package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One opened item: the KiteFFmpeg source, and the blocking bridge when the item brought its own
 * reader. An interrupt must reach the bridge as well as the source, because FFmpeg's own flag
 * cannot end a read that waits inside the reader.
 */
internal class OpenedItem(val source: MediaSource, val bridge: BlockingMediaIo?)

/**
 * Opens a KiteFFmpeg source for [item]. Playback, thumbnails, waveforms and loudness all open
 * through here, so each gets the same options: [preOpenOptions] builds them from the typed fields
 * and the raw ones, and refuses a collision before any reader exists. The bytes come from the
 * item's own reader when it has one, then from a descriptor the item names when it can be read by
 * position, otherwise from the URL fallback: FFmpeg's own protocols on the URI. The caller closes
 * the source.
 *
 * Cancelling the calling coroutine ends an open that reads through the item's reader: the bridge
 * is interrupted, FFmpeg fails the open, and this throws a `CancellationException` rather than a
 * media error. An open through the URL fallback cannot be reached that way, because FFmpeg's
 * interrupt exists only on the source that the open returns.
 */
internal suspend fun openItem(item: MediaItem): OpenedItem {
    val options = preOpenOptions(item)
    // Called once per open: the reader it makes belongs to this source and is closed with it.
    val io = item.io?.open()
    // FFmpeg's fd protocol takes the "fd" key only with this exact URI.
    val descriptor = if (io == null && item.uri == "fd:") options["fd"]?.toIntOrNull()?.let(::descriptorByteSource) else null
    return when {
        // The custom AVIO bridge: the reader carries the media, with no path and no FFmpeg protocol.
        io != null -> {
            val bridge = BlockingMediaIo(io)
            val source = try {
                interruptedWhenCancelled(bridge) { MediaSource.open(bridge, options) }
            } catch (failure: Throwable) {
                // FFmpeg reports the interrupted read as a broken input. A cancelled open is a
                // cancellation, and the caller must see one.
                currentCoroutineContext().ensureActive()
                throw failure
            }
            if (!currentCoroutineContext().isActive) {
                // FFmpeg finished with what it had read before the cancellation reached it.
                source.close()
                currentCoroutineContext().ensureActive()
            }
            OpenedItem(source, bridge)
        }
        // No protocol reads the descriptor now, so no protocol is left to consume its key.
        descriptor != null -> OpenedItem(MediaSource.open(descriptor, options - "fd"), null)
        else -> OpenedItem(openUrlFallback(item.uri, options), null)
    }
}

/**
 * Runs [open] with [bridge] interrupted the moment the calling coroutine is cancelled. The open
 * blocks this thread inside FFmpeg, so the cancellation cannot arrive any other way.
 */
private suspend inline fun <T> interruptedWhenCancelled(bridge: BlockingMediaIo, open: () -> T): T {
    val caller = currentCoroutineContext()[Job] ?: return open()
    // A child job completes as soon as its parent is cancelled, even while this thread is blocked
    // below, and its handler runs on the thread that cancelled. Completing it afterwards detaches
    // it, so the caller's job can finish.
    val link = Job(caller)
    link.invokeOnCompletion { cause -> if (cause != null) bridge.interrupt() }
    try {
        return open()
    } finally {
        link.complete()
    }
}

/** [openItem] for the callers that need only the source: thumbnails, waveforms and loudness. */
internal suspend fun openSource(item: MediaItem): MediaSource = openItem(item).source

/**
 * The URL fallback: FFmpeg's own protocols read the URI, for an item with no reader and no
 * resolver answer. The build's protocols are file, fd, pipe, data, http and tcp, so https needs the
 * network module. Every http and tcp read waits at most [URL_FALLBACK_READ_TIMEOUT]; FFmpeg limits
 * the connection itself to 5 seconds. The fallback does not reconnect, and it cannot be interrupted
 * during the open, because FFmpeg's interrupt exists only on the source the open returns.
 */
private fun openUrlFallback(uri: String, options: Map<String, String>): MediaSource {
    val bounded = urlFallbackOptions(uri, options)
    // Keys the demuxer did not consume come back from KiteFFmpeg instead of being dropped.
    return if (bounded.isEmpty()) MediaSource.open(uri) else MediaSource.open(uri, bounded)
}

/**
 * The options the URL fallback opens [uri] with: [options], plus FFmpeg's `rw_timeout` for an http
 * or tcp URI whose item does not set it. `rw_timeout` is in microseconds and limits each read, and
 * FFmpeg copies it from the http context to the tcp context under it. An item that sets its own
 * `rw_timeout` in `MediaItem.openOptions` keeps it.
 */
internal fun urlFallbackOptions(uri: String, options: Map<String, String>): Map<String, String> {
    val network = uri.startsWith("http://", ignoreCase = true) || uri.startsWith("tcp://", ignoreCase = true)
    if (!network || "rw_timeout" in options) return options
    return options + ("rw_timeout" to URL_FALLBACK_READ_TIMEOUT.inWholeMicroseconds.toString())
}

/** How long one read of the URL fallback waits for bytes over http or tcp. */
internal val URL_FALLBACK_READ_TIMEOUT: Duration = 10.seconds
