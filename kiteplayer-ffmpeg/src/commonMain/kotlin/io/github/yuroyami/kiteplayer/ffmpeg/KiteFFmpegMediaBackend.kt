// This module IS the one that speaks FFmpeg, so the raw-syntax opt-in belongs here: the annotation
// exists to name that coupling, not to forbid it. MediaItem.videoFilter is an FFmpeg filter chain
// and only this backend can act on one.
@file:OptIn(KiteFFmpegLowLevelApi::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackError
import io.github.yuroyami.kiteplayer.PlaybackException
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioDecoderFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi

/**
 * The FFmpeg backend, as one session-shaped object.
 *
 * This is what the engine is handed on a target where KiteFFmpeg exists. It opens the container and
 * returns the cursor over it together with the decoder factories that belong to that cursor, so nothing
 * above it ever has to know that the source and the decoders are the same implementation underneath.
 * The engine used to reach these factories by downcasting the source, which is the defect this removes.
 *
 * @param onWarning where decoder degradations go. Colour approximation and a permitted hardware-to-
 *        software fallback are both reported here. The callback runs on the decoder's own worker, so
 *        it must be cheap and must not block. The default discards.
 */
public class KiteFFmpegMediaBackend(
    private val onWarning: (PlaybackWarning) -> Unit = {},
    /**
     * Decoder configuration as `av_opt_set` strings, applied to every video decoder this backend
     * opens. `PlaybackProfile.decoderOptions` is the intended producer; a
     * wrong key fails the decoder open with the funnel's own typed error.
     */
    private val decoderOptions: Map<String, String> = emptyMap(),
    /** Open video decoders in low-delay shape (the LowLatency profile). */
    private val lowDelayDecode: Boolean = false,
) : MediaBackend {

    /** The option pairs exactly as configured, printed by the diagnostics dump. */
    override fun describeForDiagnostics(): String =
        "KiteFFmpegMediaBackend(decoderOptions=$decoderOptions, lowDelayDecode=$lowDelayDecode)"

    /**
     * External subtitle files, ASS included: the pure parsers this module ships. East Asian files
     * are read with the tables of kiteplayer-subtitles, the same on every target.
     */
    override fun subtitleFileParser(): io.github.yuroyami.kiteplayer.spi.SubtitleFileParser =
        object : io.github.yuroyami.kiteplayer.spi.SubtitleFileParser {
            override fun parse(text: String, vttHint: Boolean): List<io.github.yuroyami.kiteplayer.subtitle.SubtitleCue> =
                when {
                    // An ASS document announces itself; the hint flags are SRT/VTT's business.
                    text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("[Script Info]", ignoreCase = true) ->
                        io.github.yuroyami.kiteplayer.subtitle.AssParser.parse(text)
                    vttHint -> io.github.yuroyami.kiteplayer.subtitle.WebVttParser.parse(text)
                    else -> io.github.yuroyami.kiteplayer.subtitle.SubRipParser.parse(text)
                }

            override fun decode(bytes: ByteArray, encoding: String): String? =
                io.github.yuroyami.kiteplayer.subtitle.EastAsianText.decode(bytes, encoding)
        }

    override suspend fun open(media: MediaItem): BackendSession {
        // MediaSource.open is where KiteFFmpeg's FFmpeg identity gate runs, before its first allocation.
        // A rejection there is not about this file and never will be: it means the linked FFmpeg does not
        // match the headers KiteFFmpeg was compiled against, so every open fails and retrying is pointless.
        // Mapping it here is what stops the engine from reporting it as SourceUnavailable, which would
        // say the bytes could not be reached. See FFmpegRuntimeCheck.kt.
        val source = mappingFFmpegRuntimeRejection { openItem(media).let { KiteFFmpegSource(it.source, it.bridge) } }
        source.onWarning = onWarning
        source.videoFilterDescription = media.videoFilter
        // The option echo's honest half: a key the demuxer never consumed did nothing,
        // and the caller hears that once, typed, instead of discovering it by measurement.
        if (source.unusedOpenOptions.isNotEmpty()) {
            onWarning(PlaybackWarning.OptionsUnused(source.unusedOpenOptions))
        }
        source.videoDecoderOptions = decoderOptions
        source.videoLowDelay = lowDelayDecode
        return KiteFFmpegBackendSession(source)
    }
}

/**
 * One opened container and its decoders.
 *
 * The lists come from the source itself, because a KiteFFmpeg decoder is opened against the very
 * container context the packets are read from. The subtitle factory decodes the TEXT formats
 * (SubRip, WebVTT, and ASS at the dialogue tier) over the packet path with no C involved;
 * bitmap formats still need a real engine, and a stream the factory refuses is
 * deselected by the engine rather than failing the open.
 */
private class KiteFFmpegBackendSession(private val kiteCodec: KiteFFmpegSource) : BackendSession {

    override val source: PlayerMediaSource get() = kiteCodec

    override fun setWarningSink(sink: (PlaybackWarning) -> Unit) {
        // The engine's reporter joins whatever listener the application installed at construction,
        // so a hardware fallback is never silent again and an app listener keeps
        // seeing what it saw before.
        val existing = kiteCodec.onWarning
        kiteCodec.onWarning = { warning ->
            existing(warning)
            sink(warning)
        }
    }

    override val videoDecoders: List<VideoDecoderFactory> = kiteCodec.videoDecoderFactories()

    override val audioDecoders: List<AudioDecoderFactory> = kiteCodec.audioDecoderFactories()

    override val subtitleDecoders: List<SubtitleDecoderFactory> =
        listOf(KiteFFmpegSubtitleDecoderFactory())

    override fun close(): Unit = kiteCodec.close()
}

/**
 * The item's typed fields respelled as the pre-open options they are, followed by the raw
 * [MediaItem.openOptions]. `headers` is the http protocol's own option, one CRLF-joined block
 * exactly as the protocol documents it. `formatHint` is a format whitelist of one, which is what
 * forcing a demuxer means to libavformat. [MediaItem.demux] becomes the keys that
 * [toFFmpegOptions] lists. On media an option cannot apply to (headers on a local file), the open
 * path's unused-option warning says so, typed.
 *
 * A raw key that a typed field also sets is refused, naming both. Letting either side win would
 * quietly undo a setting the caller made on purpose.
 *
 * Internal rather than private for exactly one reason: its unit test, which needs no FFmpeg.
 *
 * @throws PlaybackException with [PlaybackError.ConfigurationInvalid] for such a collision.
 */
internal fun preOpenOptions(media: MediaItem): Map<String, String> {
    // Each typed option with the name of the item field that sets it.
    val typed = LinkedHashMap<String, Pair<String, String>>()
    if (media.headers.isNotEmpty()) {
        val block = media.headers.entries.joinToString(separator = "") { (key, value) -> "$key: $value\r\n" }
        typed["headers"] = block to "headers"
    }
    media.formatHint?.let { hint -> typed["format_whitelist"] = hint to "formatHint" }
    for ((key, value) in media.demux.toFFmpegOptions()) typed[key] = value to "demux"

    for (key in media.openOptions.keys) {
        val field = typed[key]?.second ?: continue
        throw PlaybackException(
            PlaybackError.ConfigurationInvalid(
                "MediaItem.openOptions sets \"$key\", and MediaItem.$field sets it too. Set it in one place only.",
            ),
        )
    }
    return typed.mapValues { it.value.first } + media.openOptions
}
