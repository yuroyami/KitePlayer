package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaIo
import io.github.yuroyami.kiteplayer.Playlists
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.StreamThumbnail
import io.github.yuroyami.kiteplayer.ThumbnailSet
import io.github.yuroyami.kiteplayer.spi.PlayerThumbnails
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.microseconds

/*
 * The seek bar pictures of an HLS stream (#433), as the image media playlists that Roku and Apple's
 * packagers write describe them: the master names an image stream with `EXT-X-IMAGE-STREAM-INF`,
 * whose RESOLUTION is the size of one tile, and the image playlist lists grid images, each with the
 * time it stands for in its `EXTINF` and its grid in the `EXT-X-TILES` before it, LAYOUT columns by
 * rows of tiles of DURATION seconds each, in reading order. The DASH reader writes a thumbnail set
 * into its HLS stand-in the same way, so both are read here.
 */

/** One image stream of a master playlist: its address as written and the size of a tile. */
internal class HlsImageStream(val uri: String, val bandwidth: Long, val tileWidth: Int?, val tileHeight: Int?)

/** The image streams [master] names, in its order, or none for a playlist that names none. */
internal fun hlsImageStreams(master: String): List<HlsImageStream> =
    master.lineSequence()
        .map { it.trim() }
        .filter { it.startsWith(IMAGE_STREAM_TAG) }
        .mapNotNull { line ->
            val attributes = parseHlsAttributes(line.substringAfter(':'))
            val uri = attributes["URI"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val resolution = attributes["RESOLUTION"]
            HlsImageStream(
                uri = uri,
                bandwidth = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                tileWidth = resolution?.substringBefore('x', "")?.trim()?.toIntOrNull()?.takeIf { it > 0 },
                tileHeight = resolution?.substringAfter('x', "")?.trim()?.toIntOrNull()?.takeIf { it > 0 },
            )
        }
        .toList()

/**
 * One grid image of an image playlist: its address, resolved, the stretch of the stream it stands
 * for, in microseconds from the stream's start, and its grid of [columns] by [rows] tiles of
 * [tileUs] each, [tileWidth] by [tileHeight] pixels when the playlist states it.
 */
internal class HlsImage(
    val uri: String,
    val startUs: Long,
    val durationUs: Long,
    val columns: Int,
    val rows: Int,
    val tileUs: Long,
    val tileWidth: Int?,
    val tileHeight: Int?,
)

/**
 * The grid images of the image playlist [text], whose relative addresses resolve against [base],
 * or null when it is not a whole one. A playlist with no end is a live stream's, whose times the
 * stream's own do not start from, so it gives none.
 */
internal fun parseHlsImagePlaylist(text: String, base: String): List<HlsImage>? {
    val lines = text.removePrefix("﻿").lineSequence().map { it.trim() }.toList()
    if (lines.firstOrNull() != "#EXTM3U") return null
    if (lines.none { it == "#EXT-X-ENDLIST" }) return null
    var columns = 1
    var rows = 1
    var tileUs: Long? = null
    var tileWidth: Int? = null
    var tileHeight: Int? = null
    var durationUs: Long? = null
    var atUs = 0L
    val images = ArrayList<HlsImage>()
    for (line in lines) {
        when {
            line.startsWith("#EXT-X-TILES:") -> {
                val attributes = parseHlsAttributes(line.substringAfter(':'))
                val layout = attributes["LAYOUT"]
                columns = layout?.substringBefore('x', "")?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 1
                rows = layout?.substringAfter('x', "")?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 1
                tileUs = attributes["DURATION"]?.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { (it * 1_000_000).toLong() }
                val resolution = attributes["RESOLUTION"]
                tileWidth = resolution?.substringBefore('x', "")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                tileHeight = resolution?.substringAfter('x', "")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            }
            line.startsWith("#EXTINF:") ->
                durationUs = line.substringAfter(':').substringBefore(',').trim().toDoubleOrNull()
                    ?.takeIf { it > 0.0 }?.let { (it * 1_000_000).toLong() }
            line.isEmpty() || line.startsWith("#") -> Unit
            else -> {
                val length = durationUs ?: continue
                val tiles = columns * rows
                images += HlsImage(
                    uri = Playlists.resolve(base, line),
                    startUs = atUs,
                    durationUs = length,
                    columns = columns,
                    rows = rows,
                    // A grid that states no tile length shares its image's evenly.
                    tileUs = tileUs ?: (length / tiles).coerceAtLeast(1L),
                    tileWidth = tileWidth,
                    tileHeight = tileHeight,
                )
                atUs += length
                durationUs = null
            }
        }
    }
    return images
}

/**
 * The tile of [images] that stands for [atUs], as the region of its grid image, or null where no
 * image stands for it. A tile whose playlist states no size takes [tileWidth] by [tileHeight], the
 * master's; a grid image of one tile and no size anywhere is the whole image.
 */
internal fun hlsTileAt(images: List<HlsImage>, atUs: Long, tileWidth: Int? = null, tileHeight: Int? = null): HlsTile? {
    val image = images.lastOrNull { it.startUs <= atUs } ?: return null
    if (atUs >= image.startUs + image.durationUs) return null
    val tiles = image.columns * image.rows
    val index = ((atUs - image.startUs) / image.tileUs).coerceIn(0L, (tiles - 1).toLong()).toInt()
    val startUs = image.startUs + index * image.tileUs
    val endUs = if (index == tiles - 1) image.startUs + image.durationUs else minOf(startUs + image.tileUs, image.startUs + image.durationUs)
    val width = image.tileWidth ?: tileWidth ?: 0
    val height = image.tileHeight ?: tileHeight ?: 0
    return HlsTile(image.uri, (index % image.columns) * width, (index / image.columns) * height, width, height, startUs, endUs)
}

/** A tile: the region [x], [y], [width] by [height] of the image at [uri], standing for [startUs] up to [endUs]. */
internal class HlsTile(
    val uri: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val startUs: Long,
    val endUs: Long,
)

/**
 * The seek bar pictures of an HLS stream read through [io] (#433): the image stream [stream] of the
 * master at [base]. The image playlist is read on the first ask, and each grid image when a
 * position on it is asked for, through [io]'s related reads, the way the stream's own segments are
 * read. The [KEPT] newest images are kept.
 */
internal class HlsThumbnails(
    private val io: MediaIo,
    private val base: String,
    private val stream: HlsImageStream,
) : PlayerThumbnails {

    override val set: ThumbnailSet = ThumbnailSet(width = stream.tileWidth, height = stream.tileHeight)

    private val lock = Mutex()
    private var images: List<HlsImage>? = null
    private var playlistRead = false
    private val kept = LinkedHashMap<String, ByteArray>()

    override suspend fun at(position: Pts): StreamThumbnail? = lock.withLock {
        val list = playlist() ?: return@withLock null
        val tile = hlsTileAt(list, position.micros, stream.tileWidth, stream.tileHeight) ?: return@withLock null
        val bytes = image(tile.uri) ?: return@withLock null
        StreamThumbnail(
            image = bytes,
            mimeType = imageMimeType(tile.uri, bytes),
            x = tile.x,
            y = tile.y,
            width = tile.width,
            height = tile.height,
            start = tile.startUs.microseconds,
            end = tile.endUs.microseconds,
        )
    }

    /** The image playlist, read once; a failed read gives no pictures for the session. */
    private suspend fun playlist(): List<HlsImage>? {
        if (playlistRead) return images
        playlistRead = true
        val address = Playlists.resolve(base, stream.uri)
        images = readRelated(address)?.let { parseHlsImagePlaylist(it.decodeToString(), address) }
        return images
    }

    private suspend fun image(uri: String): ByteArray? {
        kept.remove(uri)?.let { bytes ->
            kept[uri] = bytes
            return bytes
        }
        val bytes = readRelated(uri) ?: return null
        kept[uri] = bytes
        while (kept.size > KEPT) kept.remove(kept.keys.first())
        return bytes
    }

    /** The bytes at [address] through [io]'s related reads, or null when they cannot be had. */
    private suspend fun readRelated(address: String): ByteArray? {
        val reader = try {
            io.openRelated(address)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            null
        } ?: return null
        return try {
            readPlaylist(reader, address)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            null
        } finally {
            reader.close()
        }
    }

    companion object {
        /** How many grid images are kept: enough for a finger that scrubs back and forth. */
        const val KEPT: Int = 4

        /** The image stream a seek bar shows: the one with the largest tiles. */
        fun choose(streams: List<HlsImageStream>): HlsImageStream? =
            streams.maxWithOrNull(compareBy<HlsImageStream>({ (it.tileWidth ?: 0) * (it.tileHeight ?: 0) }, { it.bandwidth }))
    }
}

/** The kind of image [bytes] are, read from their first bytes, else guessed from [uri]. */
internal fun imageMimeType(uri: String, bytes: ByteArray): String? {
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

private const val IMAGE_STREAM_TAG = "#EXT-X-IMAGE-STREAM-INF:"
