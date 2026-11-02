package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.CEA708_CAPTIONS_CODEC
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
 *
 * The same bytes carry CEA-708 (#678), whose services the engine splits apart with [DtvccServices]
 * and lists as tracks of their own, Service 1, Service 2 and so on, from the first packet of each.
 */

/** Where the indexes of these tracks start, past any stream a container numbers. */
private const val CAPTION_TRACK_BASE: Int = 1 shl 24

/** A caption track's service sits above the video stream's index, which stays below 1 shl 16. */
private const val SERVICE_SHIFT: Int = 16

/**
 * The index of the caption track inside the video stream at [videoIndex]: CC1 for [service] 0, and
 * the CEA-708 service of that number otherwise (#678).
 */
internal fun captionTrackIndex(videoIndex: Int, service: Int = 0): Int =
    CAPTION_TRACK_BASE + (service shl SERVICE_SHIFT) + videoIndex

/** True for the index of a caption track the engine made of the captions inside a picture. */
internal fun isCaptionTrack(index: Int): Boolean = index >= CAPTION_TRACK_BASE

/** The index of the video stream whose pictures carry the caption track at [index]. */
internal fun captionVideoOf(index: Int): Int = (index - CAPTION_TRACK_BASE) and ((1 shl SERVICE_SHIFT) - 1)

/**
 * The track of CEA-708 [service] inside [video] (#678). Service 1 is the primary caption service,
 * which speaks the picture's language; the others name no language, because only the transport
 * stream's caption service descriptor does, and the engine reads none.
 */
internal fun cea708StreamOf(video: PlayerStreamInfo, service: Int): PlayerStreamInfo = PlayerStreamInfo(
    index = captionTrackIndex(video.index, service),
    kind = TrackKind.Subtitle,
    codec = CEA708_CAPTIONS_CODEC,
    language = video.language.takeIf { service == 1 },
    title = "Service $service",
    isAccessibility = true,
    isSparse = true,
)

/**
 * True when [triplets] are whole `cc_data` triplets that carry CEA-708 and no valid CEA-608 pair
 * (#678), so a CC1 track of them would never show anything.
 */
internal fun onlyCea708(triplets: ByteArray): Boolean {
    if (triplets.size % 3 != 0) return false
    var dtvcc = false
    for (at in triplets.indices step 3) {
        val marker = triplets[at].toInt()
        if (marker and 0x04 == 0) continue
        if (marker and 0x02 == 0) return false
        dtvcc = true
    }
    return dtvcc
}

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
