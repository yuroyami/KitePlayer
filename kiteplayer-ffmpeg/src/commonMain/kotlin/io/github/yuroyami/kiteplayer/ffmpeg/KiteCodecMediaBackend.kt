@file:OptIn(KiteCodecLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.PlaybackWarning
import io.github.yuroyami.kiteplayer.spi.AudioDecoderFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.PlayerMediaSource
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory
import io.github.yuroyami.kiteplayer.spi.VideoDecoderFactory
import io.github.yuroyami.kitecodec.KiteCodecLowLevelApi
import io.github.yuroyami.kitecodec.MediaSource

/**
 * The FFmpeg backend, as one session-shaped object.
 *
 * This is what the engine is handed on a target where KiteCodec exists. It opens the container and
 * returns the cursor over it together with the decoder factories that belong to that cursor, so nothing
 * above it ever has to know that the source and the decoders are the same implementation underneath.
 * The engine used to reach these factories by downcasting the source, which is the defect this removes.
 *
 * @param onWarning where decoder degradations go. Colour approximation and a permitted hardware-to-
 *        software fallback are both reported here. The callback runs on the decoder's own worker, so
 *        it must be cheap and must not block. The default discards.
 */
public class KiteCodecMediaBackend(
    private val onWarning: (PlaybackWarning) -> Unit = {},
    /**
     * Decoder configuration as `av_opt_set` strings, applied to every video decoder this backend
     * opens (KPKMP 17.10, KD-6). `PlaybackProfile.decoderOptions` is the intended producer; a
     * wrong key fails the decoder open with the funnel's own typed error.
     */
    private val decoderOptions: Map<String, String> = emptyMap(),
    /** Open video decoders in low-delay shape (KD-6's LowLatency profile). */
    private val lowDelayDecode: Boolean = false,
) : MediaBackend {

    /** KD-7's echo: the option pairs exactly as configured, printed by the diagnostics dump. */
    override fun describeForDiagnostics(): String =
        "KiteCodecMediaBackend(decoderOptions=$decoderOptions, lowDelayDecode=$lowDelayDecode)"

    /** External subtitle files (S4.e): the text parsers this module already ships. */
    override fun subtitleFileParser(): io.github.yuroyami.kiteplayer.spi.SubtitleFileParser =
        io.github.yuroyami.kiteplayer.spi.SubtitleFileParser { text, vttHint ->
            if (vttHint) {
                io.github.yuroyami.kiteplayer.subtitle.WebVttParser.parse(text)
            } else {
                io.github.yuroyami.kiteplayer.subtitle.SubRipParser.parse(text)
            }
        }

    override suspend fun open(media: MediaItem): BackendSession {
        require(media.io == null) {
            "Custom I/O is not wired yet. KiteCodec has no custom-input path."
        }
        // MediaSource.open is where KiteCodec's FFmpeg identity gate runs, before its first allocation.
        // A rejection there is not about this file and never will be: it means the linked FFmpeg does not
        // match the headers KiteCodec was compiled against, so every open fails and retrying is pointless.
        // Mapping it here is what stops the engine from reporting it as SourceUnavailable, which would
        // say the bytes could not be reached. See FFmpegRuntimeCheck.kt.
        val source = mappingFFmpegRuntimeRejection {
            KiteCodecSource(
                if (media.openOptions.isEmpty()) {
                    MediaSource.open(media.uri)
                } else {
                    // KD-4's pre-open funnel. Unconsumed keys are reported by KiteCodec rather
                    // than dropped, so a typo surfaces instead of quietly doing nothing.
                    MediaSource.open(media.uri, media.openOptions)
                },
            )
        }
        source.onWarning = onWarning
        source.videoFilterDescription = media.videoFilter
        source.videoDecoderOptions = decoderOptions
        source.videoLowDelay = lowDelayDecode
        return KiteCodecBackendSession(source)
    }
}

/**
 * One opened container and its decoders.
 *
 * The lists come from the source itself, because a KiteCodec decoder is opened against the very
 * container context the packets are read from. The subtitle factory decodes the TEXT formats
 * (SubRip, WebVTT) over the packet path with no C involved (S4.c); ASS and bitmap formats are
 * S4.f's, and a stream the factory refuses is deselected by the engine rather than failing the
 * open.
 */
private class KiteCodecBackendSession(private val kiteCodec: KiteCodecSource) : BackendSession {

    override val source: PlayerMediaSource get() = kiteCodec

    override fun setWarningSink(sink: (PlaybackWarning) -> Unit) {
        // The engine's reporter joins whatever listener the application installed at construction,
        // so a hardware fallback is never silent again (audit P1-21) and an app listener keeps
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
        listOf(KiteCodecSubtitleDecoderFactory())

    override fun close(): Unit = kiteCodec.close()
}
