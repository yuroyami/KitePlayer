// This module IS the one that speaks FFmpeg, so the raw-syntax opt-in belongs here: the annotation
// exists to name that coupling, not to forbid it. MediaItem.videoFilter is an FFmpeg filter chain
// and only this backend can act on one.
@file:OptIn(KiteFFmpegLowLevelApi::class, io.github.yuroyami.kiteplayer.KitePlayerLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioDecoderFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi
import io.github.yuroyami.kiteffmpeg.MediaSource

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
     * opens (KD-6). `PlaybackProfile.decoderOptions` is the intended producer; a
     * wrong key fails the decoder open with the funnel's own typed error.
     */
    private val decoderOptions: Map<String, String> = emptyMap(),
    /** Open video decoders in low-delay shape (KD-6's LowLatency profile). */
    private val lowDelayDecode: Boolean = false,
) : MediaBackend {

    /** KD-7's echo: the option pairs exactly as configured, printed by the diagnostics dump. */
    override fun describeForDiagnostics(): String =
        "KiteFFmpegMediaBackend(decoderOptions=$decoderOptions, lowDelayDecode=$lowDelayDecode)"

    /** External subtitle files (S4.e, ASS since 17.12 M2): the pure parsers this module ships. */
    override fun subtitleFileParser(): io.github.yuroyami.kiteplayer.spi.SubtitleFileParser =
        io.github.yuroyami.kiteplayer.spi.SubtitleFileParser { text, vttHint ->
            when {
                // An ASS document announces itself; the hint flags are SRT/VTT's business.
                text.trimStart('\uFEFF', ' ', '\r', '\n').startsWith("[Script Info]", ignoreCase = true) ->
                    io.github.yuroyami.kiteplayer.subtitle.AssParser.parse(text)
                vttHint -> io.github.yuroyami.kiteplayer.subtitle.WebVttParser.parse(text)
                else -> io.github.yuroyami.kiteplayer.subtitle.SubRipParser.parse(text)
            }
        }

    override suspend fun open(media: MediaItem): BackendSession {
        // MediaSource.open is where KiteFFmpeg's FFmpeg identity gate runs, before its first allocation.
        // A rejection there is not about this file and never will be: it means the linked FFmpeg does not
        // match the headers KiteFFmpeg was compiled against, so every open fails and retrying is pointless.
        // Mapping it here is what stops the engine from reporting it as SourceUnavailable, which would
        // say the bytes could not be reached. See FFmpegRuntimeCheck.kt.
        val options = preOpenOptions(media)
        rewindFdOption(options)
        // Invoked exactly once: the item carries a factory, and the reader it makes belongs to this
        // session and is closed with it.
        val io = media.io?.invoke()
        val source = mappingFFmpegRuntimeRejection {
            KiteFFmpegSource(
                when {
                    // M1, the custom AVIO bridge: the item's own byte reader carries the media,
                    // demuxed by FFmpeg with no path and no FFmpeg protocol involved.
                    io != null -> MediaSource.open(BlockingMediaIo(io), options)
                    options.isEmpty() -> MediaSource.open(media.uri)
                    // KD-4's pre-open funnel. Unconsumed keys are reported by KiteFFmpeg rather
                    // than dropped, so a typo surfaces instead of quietly doing nothing.
                    else -> MediaSource.open(media.uri, options)
                },
            )
        }
        source.onWarning = onWarning
        source.videoFilterDescription = media.videoFilter
        // The option echo's honest half (S4.e): a key the demuxer never consumed did nothing,
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
 * (SubRip, WebVTT, and ASS at the M2 dialogue tier) over the packet path with no C involved
 * (S4.c); bitmap formats still need a real engine, and a stream the factory refuses is
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
 * The item's typed fields respelled as the pre-open options they are: `headers` is
 * the http protocol's own option, one CRLF-joined block exactly as the protocol documents it,
 * and `formatHint` is a format whitelist of one, which is what forcing a demuxer means to
 * libavformat. An explicit [MediaItem.openOptions] key wins over the typed field, because the
 * raw funnel is the escape hatch and an escape hatch that sugar can override is not one. On
 * media an option cannot apply to (headers on a local file), the open path's unused-option
 * warning says so, typed.
 *
 * Internal rather than private for exactly one reason: its unit test, which needs no FFmpeg.
 */
internal fun preOpenOptions(media: MediaItem): Map<String, String> = buildMap {
    putAll(media.openOptions)
    if (media.headers.isNotEmpty() && !containsKey("headers")) {
        put("headers", media.headers.entries.joinToString(separator = "") { (key, value) -> "$key: $value\r\n" })
    }
    val hint = media.formatHint
    if (hint != null && !containsKey("format_whitelist")) {
        put("format_whitelist", hint)
    }
}
