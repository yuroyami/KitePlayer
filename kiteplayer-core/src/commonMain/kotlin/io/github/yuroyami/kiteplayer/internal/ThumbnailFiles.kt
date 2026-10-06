package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Playlists
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.StreamThumbnail
import io.github.yuroyami.kiteplayer.ThumbnailSet
import io.github.yuroyami.kiteplayer.spi.PlayerThumbnails
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.microseconds

/**
 * One cue of a WebVTT thumbnail file (#433): the image at [image], resolved, and the region of it
 * that stands for [startUs] up to [endUs], or the whole image when the cue names no region.
 */
internal class ThumbnailCue(
    val startUs: Long,
    val endUs: Long,
    val image: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/**
 * The cues of the WebVTT thumbnail file [text] at [base], in time order. A cue's text is the address
 * of an image, relative to the file, with a media fragment that names a region of it in pixels,
 * `sprite.jpg#xywh=480,0,160,90`, as the W3C media fragments define `xywh`. A cue with no region
 * stands for the whole image, and one whose region is not in pixels or whose text names nothing is
 * left out.
 */
internal fun parseThumbnailVtt(text: String, base: String): List<ThumbnailCue> {
    val lines = text.removePrefix("﻿").replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val cues = ArrayList<ThumbnailCue>()
    var at = 0
    while (at < lines.size) {
        val line = lines[at].trim()
        val arrow = line.indexOf("-->")
        if (arrow < 0) {
            at++
            continue
        }
        val start = vttTime(line.substring(0, arrow).trim())
        val end = vttTime(line.substring(arrow + 3).trim().substringBefore(' ').substringBefore('\t'))
        val payload = lines.getOrNull(at + 1)?.trim().orEmpty()
        at += 2
        if (start == null || end == null || end <= start || payload.isEmpty()) continue
        val address = payload.substringBefore('#')
        if (address.isEmpty()) continue
        val fragment = payload.substringAfter('#', "")
        val region = fragment.split('&').firstOrNull { it.startsWith("xywh=") }?.removePrefix("xywh=")
        val numbers = when {
            region == null -> listOf(0, 0, 0, 0)
            region.startsWith("percent:") -> continue
            else -> region.removePrefix("pixel:").split(',').map { it.trim().toIntOrNull() ?: -1 }
        }
        if (numbers.size != 4 || numbers.any { it < 0 }) continue
        cues += ThumbnailCue(start, end, Playlists.resolve(base, address), numbers[0], numbers[1], numbers[2], numbers[3])
    }
    return cues.sortedBy { it.startUs }
}

/** A WebVTT timestamp, `hh:mm:ss.ttt` or `mm:ss.ttt`, in microseconds, or null when it is not one. */
private fun vttTime(text: String): Long? {
    val parts = text.split(':')
    if (parts.size !in 2..3) return null
    val seconds = parts.last()
    val whole = seconds.substringBefore('.').toLongOrNull() ?: return null
    val fraction = seconds.substringAfter('.', "").padEnd(3, '0').take(3).toLongOrNull() ?: return null
    val minutes = parts[parts.size - 2].toLongOrNull() ?: return null
    val hours = if (parts.size == 3) parts[0].toLongOrNull() ?: return null else 0L
    if (whole >= 60 || minutes >= 60 || hours < 0) return null
    return ((hours * 60 + minutes) * 60 + whole) * 1_000_000L + fraction * 1_000L
}

/**
 * The seek bar pictures of an item's WebVTT thumbnail file (#433), its [cues], whose images
 * [fetch] reads when a position on them is asked for. The [KEPT] newest images are kept.
 */
internal class FileThumbnails(
    private val cues: List<ThumbnailCue>,
    private val fetch: suspend (String) -> ByteArray?,
) : PlayerThumbnails {

    override val set: ThumbnailSet = cues.first().let { first ->
        val lengths = cues.map { it.endUs - it.startUs }.distinct()
        ThumbnailSet(
            width = first.width.takeIf { it > 0 },
            height = first.height.takeIf { it > 0 },
            interval = lengths.singleOrNull()?.microseconds,
        )
    }

    private val lock = Mutex()
    private val kept = LinkedHashMap<String, ByteArray>()

    override suspend fun at(position: Pts): StreamThumbnail? {
        val atUs = position.micros
        val cue = cues.lastOrNull { it.startUs <= atUs }?.takeIf { atUs < it.endUs } ?: return null
        val bytes = lock.withLock { image(cue.image) } ?: return null
        return StreamThumbnail(
            image = bytes,
            mimeType = thumbnailMimeType(cue.image, bytes),
            x = cue.x,
            y = cue.y,
            width = cue.width,
            height = cue.height,
            start = cue.startUs.microseconds,
            end = cue.endUs.microseconds,
        )
    }

    private suspend fun image(uri: String): ByteArray? {
        kept.remove(uri)?.let { bytes ->
            kept[uri] = bytes
            return bytes
        }
        val bytes = fetch(uri) ?: return null
        kept[uri] = bytes
        while (kept.size > KEPT) kept.remove(kept.keys.first())
        return bytes
    }

    companion object {
        /** How many images are kept: enough for a finger that scrubs back and forth. */
        const val KEPT: Int = 4
    }
}

/** The kind of image [bytes] are, read from their first bytes, else guessed from [uri]. */
internal fun thumbnailMimeType(uri: String, bytes: ByteArray): String? {
    fun at(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF)
    return when {
        at(0) == 0xFF && at(1) == 0xD8 -> "image/jpeg"
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
        bytes.size >= 12 && bytes.copyOfRange(0, 4).decodeToString() == "RIFF" &&
            bytes.copyOfRange(8, 12).decodeToString() == "WEBP" -> "image/webp"
        else -> when (uri.substringBefore('?').substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> null
        }
    }
}

/**
 * Where the seek bar pictures of the item that plays come from, [thumbnails], and the item's place
 * in its file: its clip's start and its end, in microseconds of the file, or null when not known.
 */
internal class ThumbnailTarget(val thumbnails: PlayerThumbnails, val clipStartUs: Long, val itemEndUs: Long?)
