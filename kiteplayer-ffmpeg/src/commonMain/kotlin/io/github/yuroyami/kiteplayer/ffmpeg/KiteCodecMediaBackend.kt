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
) : MediaBackend {

    override suspend fun open(media: MediaItem): BackendSession {
        require(media.io == null) {
            "Custom I/O is not wired yet. KiteCodec has no custom-input path."
        }
        // MediaSource.open is where KiteCodec's FFmpeg identity gate runs, before its first allocation.
        // A rejection there is not about this file and never will be: it means the linked FFmpeg does not
        // match the headers KiteCodec was compiled against, so every open fails and retrying is pointless.
        // Mapping it here is what stops the engine from reporting it as SourceUnavailable, which would
        // say the bytes could not be reached. See FFmpegRuntimeCheck.kt.
        val source = mappingFFmpegRuntimeRejection { KiteCodecSource(MediaSource.open(media.uri)) }
        source.onWarning = onWarning
        return KiteCodecBackendSession(source)
    }
}

/**
 * One opened container and its decoders.
 *
 * The lists come from the source itself, because a KiteCodec decoder is opened against the very
 * container context the packets are read from. There is no subtitle decoder in this build: KiteCodec
 * decodes no subtitle stream yet, so the list is empty rather than holding a factory that always
 * refuses. An empty list is how a backend says "not this kind of stream", and the engine deselects such
 * a stream instead of failing the open.
 */
private class KiteCodecBackendSession(private val kiteCodec: KiteCodecSource) : BackendSession {

    override val source: PlayerMediaSource get() = kiteCodec

    override val videoDecoders: List<VideoDecoderFactory> = kiteCodec.videoDecoderFactories()

    override val audioDecoders: List<AudioDecoderFactory> = kiteCodec.audioDecoderFactories()

    override val subtitleDecoders: List<SubtitleDecoderFactory> = emptyList()

    override fun close(): Unit = kiteCodec.close()
}
