package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.CLOSED_CAPTIONS_CODEC
import io.github.yuroyami.kiteplayer.spi.PlayerPacket
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo

/*
 * The closed captions inside a video stream (#236), as broadcast H.264, HEVC and MPEG-2 carry them
 * in the A/53 data of their pictures, with no subtitle stream of their own. The video lane reads
 * each decoded picture's captions and queues them as the packets of a subtitle track the engine
 * makes, CC1, so the track is chosen, cached, timed and drawn as any container subtitle track is.
 * The pictures leave the decoder in the order they are shown, which is the order the captions
 * were written in, and ahead of the moment playing, so each caption reaches its track ahead of
 * its time too. mpv makes a track of them the same way, from the first picture that carries any.
 */

/** Where the indexes of these tracks start, past any stream a container numbers. */
private const val CAPTION_TRACK_BASE: Int = 1 shl 24

/** The index of the caption track inside the video stream at [videoIndex]. */
internal fun captionTrackIndex(videoIndex: Int): Int = CAPTION_TRACK_BASE + videoIndex

/** True for the index of a caption track the engine made of the captions inside a picture. */
internal fun isCaptionTrack(index: Int): Boolean = index >= CAPTION_TRACK_BASE

/** The caption track of [video], which speaks the picture's language. */
internal fun captionStreamOf(video: PlayerStreamInfo): PlayerStreamInfo = PlayerStreamInfo(
    index = captionTrackIndex(video.index),
    kind = TrackKind.Subtitle,
    codec = CLOSED_CAPTIONS_CODEC,
    language = video.language,
    title = "CC1",
    isAccessibility = true,
    isSparse = true,
)

/** One picture's caption bytes, as a packet of its caption track. */
internal class CaptionPacket(
    override val streamIndex: Int,
    override val pts: Pts,
    private val bytes: ByteArray,
) : PlayerPacket {
    override val dts: Pts get() = pts
    override val duration: Pts? get() = null
    override val isKeyframe: Boolean get() = true
    override val sizeBytes: Int get() = bytes.size
    override fun copyBytes(): ByteArray = bytes.copyOf()
    override val bytePosition: Long? get() = null
    override fun close() = Unit
}
