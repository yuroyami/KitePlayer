@file:OptIn(io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteffmpeg.MediaByteSource
import io.github.yuroyami.kiteffmpeg.MediaSource
import io.github.yuroyami.kiteffmpeg.MediaType
import io.github.yuroyami.kiteffmpeg.StreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleFileReading
import io.github.yuroyami.kiteplayer.subtitle.AssParser
import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * External subtitle files in the formats the Kotlin readers do not know, read by FFmpeg's own
 * subtitle demuxers and decoders (#492): SAMI, MicroDVD, SubViewer and SBV, MPL2, JACOsub, VPlayer,
 * PJS, RealText, Spruce STL, Scenarist SCC, and the image tracks of VobSub and Blu-ray `.sup` files.
 *
 * A text format is handed to FFmpeg as the engine read it, in UTF-8, so the encoding the engine
 * decided holds, as mpv's `sub-codepage` does. An image format is handed to FFmpeg as its bytes. A
 * VobSub index on disk is opened by its path, because FFmpeg reads the pictures from the `.sub` file
 * beside it. The times stay the file's own: a subtitle file's first line is not its origin.
 */
internal object SubtitleFiles {

    suspend fun read(bytes: ByteArray, text: String, uri: String): SubtitleFileReading? =
        withContext(Dispatchers.Default) {
            try {
                readNow(bytes, text, uri)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                // FFmpeg has no reader for it, or the bytes are not what their name says.
                null
            }
        }

    private fun readNow(bytes: ByteArray, text: String, uri: String): SubtitleFileReading? {
        val path = localPath(uri)
        if (path != null && uri.endsWith(".idx", ignoreCase = true)) return open(MediaSource.open(path), text)
        // A VobSub track given by its pictures: the index beside them holds their palette and size.
        if (path != null && uri.endsWith(".sub", ignoreCase = true) && startsLikeMpegProgram(bytes)) {
            val index = path.dropLast(".sub".length) + ".idx"
            runCatching { open(MediaSource.open(index), text) }.getOrNull()?.let { return it }
        }
        // An image track's bytes are no text, and a text track's encoding is the engine's call.
        val asBytes = open(MediaSource.open(BytesSource(bytes)), text, imagesOnly = true)
        if (asBytes != null) return asBytes
        val utf8 = text.encodeToByteArray()
        return open(MediaSource.open(BytesSource(utf8)), text)
    }

    /** The cues of the first subtitle stream of [source], which this closes; null when it has none to give. */
    private fun open(source: MediaSource, text: String, imagesOnly: Boolean = false): SubtitleFileReading? = source.use {
        val stream = source.streams.firstOrNull { it.type == MediaType.Subtitle } ?: return null
        val images = stream.codec.name in IMAGE_CODECS
        if (imagesOnly && !images) return null
        val cues = decodeAll(source, stream, images)
        if (cues.isEmpty()) return null
        val format = source.formatName.substringBefore(',')
        SubtitleFileReading(
            cues = cues,
            format = format,
            assumedFrameRate = if (format == "microdvd" && !namesItsRate(text)) MICRODVD_DEFAULT_RATE else null,
        )
    }

    private fun decodeAll(source: MediaSource, stream: StreamInfo, images: Boolean): List<SubtitleCue> {
        val track = AssParser.trackParser("")
        val cues = mutableListOf<SubtitleCue>()
        source.openSubtitleDecoder(stream).use { decoder ->
            source.openPacketReader(listOf(stream)).use { reader ->
                while (cues.size < MAX_CUES) {
                    val packet = reader.read()
                    val subtitle = try {
                        if (packet == null) decoder.drain() else decoder.decode(packet)
                    } catch (_: io.github.yuroyami.kiteffmpeg.FFmpegException) {
                        null
                    }
                    val packetStart = packet?.ptsMicros
                    val packetEnd = packet?.durationMicros?.let { duration -> packetStart?.plus(duration) }
                    packet?.close()
                    if (subtitle != null) {
                        val start = subtitle.startMicros ?: packetStart
                        if (start != null) {
                            val end = subtitle.endMicros?.takeIf { it > start }
                                ?: packetEnd?.takeIf { it > start }
                                ?: SubtitleCue.OPEN_END
                            if (images) {
                                cues += picture(subtitle, start, end)
                            } else {
                                subtitle.texts.forEach { event -> track.parseEvent(event, start, end)?.let(cues::add) }
                            }
                        }
                    }
                    if (packet == null) break
                }
            }
        }
        return closed(cues)
    }

    private fun picture(subtitle: io.github.yuroyami.kiteffmpeg.Subtitle, start: Long, end: Long): SubtitleCue.Bitmap {
        val images = subtitle.images.filter { it.width > 0 && it.height > 0 }
        val width = subtitle.canvasWidth.takeIf { it > 0 } ?: images.maxOfOrNull { it.x + it.width } ?: 1
        val height = subtitle.canvasHeight.takeIf { it > 0 } ?: images.maxOfOrNull { it.y + it.height } ?: 1
        return SubtitleCue.Bitmap(
            startMicros = start,
            endMicros = end,
            regions = images.map { image ->
                BitmapRegion(
                    x = image.x,
                    y = image.y,
                    width = image.width,
                    height = image.height,
                    canvasWidth = width,
                    canvasHeight = height,
                    bitmap = RgbaBitmap(image.width, image.height, image.rgba),
                    forced = image.forced,
                )
            },
        )
    }

    /**
     * [cues] sorted, each open end closed at the next later start, as a Blu-ray track ends a picture
     * with the next one, and the pictures that only clear the screen left out, their work done. A
     * last cue still open runs for [LAST_OPEN_CUE_MICROS].
     */
    private fun closed(cues: List<SubtitleCue>): List<SubtitleCue> {
        val sorted = cues.sortedBy { it.startMicros }
        return sorted.mapIndexedNotNull { index, cue ->
            val closedCue = if (cue.endMicros != SubtitleCue.OPEN_END) {
                cue
            } else {
                val next = sorted.subList(index + 1, sorted.size).firstOrNull { it.startMicros > cue.startMicros }
                cue.endingAt(next?.startMicros ?: (cue.startMicros + LAST_OPEN_CUE_MICROS))
            }
            closedCue.takeUnless { it is SubtitleCue.Bitmap && it.regions.isEmpty() }
        }
    }

    private fun SubtitleCue.endingAt(end: Long): SubtitleCue = when (this) {
        is SubtitleCue.Text -> copy(endMicros = end)
        is SubtitleCue.Bitmap -> copy(endMicros = end)
    }

    /** Whether a MicroDVD file's first line is the frame-rate line, `{1}{1}25.000`, which FFmpeg reads its rate from. */
    private fun namesItsRate(text: String): Boolean {
        val first = text.trimStart('﻿').lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        return MICRODVD_RATE_LINE.matches(first.trim())
    }

    /** An MPEG program stream's pack header, which a VobSub `.sub` file opens with. */
    private fun startsLikeMpegProgram(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && bytes[2] == 1.toByte() &&
            bytes[3] == 0xBA.toByte()

    /** A local file's path for [uri], or null for an address FFmpeg must not be handed. */
    private fun localPath(uri: String): String? = when {
        uri.startsWith("file://") -> uri.removePrefix("file://")
        uri.startsWith("/") -> uri
        uri.length > 2 && uri[1] == ':' && (uri[2] == '\\' || uri[2] == '/') -> uri
        else -> null
    }

    /** A file the engine already read, handed to FFmpeg whole. */
    private class BytesSource(private val bytes: ByteArray) : MediaByteSource {
        private var position = 0
        override val size: Long get() = bytes.size.toLong()
        override val seekable: Boolean get() = true

        override fun read(into: ByteArray, offset: Int, length: Int): Int {
            if (position >= bytes.size) return -1
            val count = minOf(length, bytes.size - position)
            bytes.copyInto(into, offset, position, position + count)
            position += count
            return count
        }

        override fun seek(position: Long) {
            this.position = position.coerceIn(0L, bytes.size.toLong()).toInt()
        }

        override fun close() = Unit
    }

    private val IMAGE_CODECS = setOf("hdmv_pgs_subtitle", "dvd_subtitle", "dvb_subtitle", "xsub")

    /** MicroDVD's frame-rate line, with or without its second frame number. */
    private val MICRODVD_RATE_LINE = Regex("\\{1\\}\\{1?\\}\\d+(\\.\\d+)?")

    /** The rate FFmpeg's MicroDVD reader counts a file without that line at, 23.976 frames a second. */
    private const val MICRODVD_DEFAULT_RATE: Double = 24_000.0 / 1_001.0

    private const val LAST_OPEN_CUE_MICROS: Long = 5_000_000

    /** As many cues as a subtitle file of the Kotlin readers may give. */
    private const val MAX_CUES: Int = 100_000
}
