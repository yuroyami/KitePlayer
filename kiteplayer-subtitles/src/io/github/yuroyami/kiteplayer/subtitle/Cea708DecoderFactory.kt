package io.github.yuroyami.kiteplayer.subtitle

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.CEA708_CAPTIONS_CODEC
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoder
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoderFactory

/**
 * Decodes the CEA-708 caption services the engine finds inside a video stream (#678), the tracks
 * whose codec is [CEA708_CAPTIONS_CODEC], in Kotlin, on every platform.
 *
 * Broadcast video in North America carries CEA-708 beside CEA-608 in the same A/53 bytes, and a
 * second language, the windows, their positions and the colours of the text only in 708. FFmpeg's
 * caption decoder reads only 608, so a backend lists this factory beside its own to offer each
 * service as a track that shows: each visible window is a cue at the window's anchor, styled by
 * its pen. The FFmpeg backend lists it.
 *
 * Each packet of such a track is one service's bytes as a picture completed them, and each answer
 * is the screen as it stands from the packet's time until the next change, as the engine shows the
 * captions inside a picture.
 */
public class Cea708DecoderFactory : SubtitleDecoderFactory {

    override val name: String = "kiteplayer-cea708"

    override suspend fun create(stream: PlayerStreamInfo): SubtitleDecoder? =
        if (stream.codec == CEA708_CAPTIONS_CODEC) Cea708Decoder() else null
}

/** One CEA-708 service's decoder: [Cea708ServiceReader] over each packet's bytes, at the packet's time. */
internal class Cea708Decoder : SubtitleDecoder {

    private val reader = Cea708ServiceReader()
    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the caption decoder is closed" }
        // The screen holds until its next change, so the end of the stream has nothing to give.
        val pts = packet?.pts ?: return true
        pending.addAll(reader.read(packet.copyBytes(), pts.micros))
        return true
    }

    override suspend fun receive(): List<SubtitleCue> {
        if (pending.isEmpty()) return emptyList()
        val out = pending.toList()
        pending.clear()
        return out
    }

    override suspend fun flush(newGeneration: Generation) {
        pending.clear()
        reader.reset()
    }

    override fun close() {
        closed = true
    }
}
