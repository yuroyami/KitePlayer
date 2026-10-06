@file:OptIn(io.github.yuroyami.kiteffmpeg.KiteFFmpegLowLevelApi::class)

package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.Generation
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.SubtitleDecoder
import io.github.yuroyami.kiteplayer.subtitle.AssParser
import io.github.yuroyami.kiteplayer.subtitle.AssTrackParser
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * The decoder of the caption track the engine makes of the captions inside a video stream (#236):
 * KiteFFmpeg's caption decoder over each packet's bytes, one picture's, at the packet's time, which
 * is already the engine's.
 *
 * The decoder answers in real time, as mpv has FFmpeg's do for every caption stream it plays: each
 * answer is the screen as it now stands, from the picture that changed it, with no end. Buffered,
 * a caption would come only as it left the screen, and the pictures are decoded too little ahead
 * of the one shown for it to be seen. So each answer is a cue with no end, which the engine ends
 * at the next cue's start, and an answer with no text is an empty cue, which clears the screen.
 * The answers are ASS events, as for a caption track of its own, so every event goes through the
 * same ASS event parser.
 */
internal class InVideoCaptionDecoder(
    private val decoder: io.github.yuroyami.kiteffmpeg.ClosedCaptionDecoder,
) : SubtitleDecoder {

    private val track: AssTrackParser = AssParser.trackParser("")
    private val pending = ArrayDeque<SubtitleCue>()
    private var closed = false

    override suspend fun send(packet: PlayerPacket?): Boolean {
        check(!closed) { "the caption decoder is closed" }
        // The screen holds until its next answer, so the end of the stream has nothing to give.
        val pts = packet?.pts ?: return true
        // Damaged captions cost their own caption and nothing more.
        val subtitle = try {
            decoder.decode(packet.copyBytes(), pts.micros)
        } catch (damaged: io.github.yuroyami.kiteffmpeg.FFmpegException) {
            null
        } ?: return true
        val start = subtitle.startMicros ?: pts.micros
        val cues = subtitle.texts.mapNotNull { event -> track.parseEvent(event, start, SubtitleCue.OPEN_END) }
        if (cues.isEmpty()) {
            pending.addLast(SubtitleCue.Text(start, SubtitleCue.OPEN_END, emptyList()))
        } else {
            cues.forEach(pending::addLast)
        }
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
        decoder.flush()
    }

    override fun close() {
        if (closed) return
        closed = true
        decoder.close()
    }
}
