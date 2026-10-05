package io.github.yuroyami.kiteplayer

import kotlin.time.Duration

/** How far the demuxer reads into a container before playback starts, to find its streams. */
public sealed interface ProbeDepth {
    /** The backend's own default. Right for a file with a proper index, which is most files. */
    public data object Default : ProbeDepth

    /** 512 KiB and 200 ms. Opens fast, and can miss a stream that starts late in a transport stream. */
    public data object Fast : ProbeDepth

    /** 64 MiB and 20 seconds. Finds every stream, and is slow on a network source. */
    public data object Thorough : ProbeDepth

    /** Reads up to [bytes] bytes and [duration] of media. Both must be positive. */
    public data class Custom(val bytes: Long, val duration: Duration) : ProbeDepth {
        init {
            require(bytes > 0 && duration > Duration.ZERO) {
                "A custom probe depth needs positive bytes and a positive duration, got $bytes and $duration"
            }
        }
    }
}

/** What the demuxer does with a packet that the container marks as damaged. */
public enum class CorruptPackets {
    /** Pass it to the decoder, which may hide the damage. The backend's default. */
    Keep,

    /** Drop it before it reaches the decoder. */
    Drop,
}

/**
 * Typed settings for opening a container. The backend applies every field, or refuses the open
 * with a typed error. It never ignores one. Each default is the backend's own default, so
 * `DemuxPolicy()` changes nothing.
 */
public data class DemuxPolicy(
    /** How far to read before playback starts. */
    val probe: ProbeDepth = ProbeDepth.Default,
    /** What to do with a damaged packet. */
    val corruptPackets: CorruptPackets = CorruptPackets.Keep,
    /** Rebuilds missing presentation timestamps from the decode order, for a file whose muxer wrote none. */
    val generateTimestamps: Boolean = false,
    /**
     * Drops the packets read while probing instead of keeping them for playback, and does not wait
     * to reorder network packets. For a live source only: on a file, playback can start after the
     * true beginning.
     */
    val lowLatency: Boolean = false,
    /** Bytes to skip before probing, for a file with junk in front of its header. */
    val skipInitialBytes: Long = 0,
    /**
     * The highest bitrate, in bits per second, of the variant that an adaptive stream plays, or
     * null for no limit. An HLS master playlist offers the same media as several variants, and the
     * backend plays the variant with the highest bitrate within this limit and [maxVideoHeight].
     * When no variant fits, it plays the one with the lowest bitrate. The player's own step up to a
     * higher variant never passes this limit either. Media with one variant has no choice to make,
     * so the limit does not apply to it.
     */
    val maxBitrate: Long? = null,
    /**
     * The tallest picture, in pixels, of the variant that an adaptive stream plays, or null for no
     * limit. It works with [maxBitrate], and a variant that does not state its size passes.
     */
    val maxVideoHeight: Int? = null,
    /**
     * The [StreamVariant.index] of the variant to play, or null to choose one by [maxBitrate] and
     * [maxVideoHeight]. An index that the master playlist does not have is ignored, and the choice
     * is made as for null. [KitePlayer.selectVariant] sets it on the item that plays. With null,
     * the player also steps down and up by itself as the network allows; a set index stays.
     */
    val variant: Int? = null,
    /**
     * The [MediaProgram.number] of the channel to play from a multiplex, or null to play the first
     * one with a picture. The player then picks the picture, the sound and the subtitles from that
     * channel's tracks only. A number the media does not have is ignored, and the choice is made as
     * for null. [KitePlayer.selectProgram] sets it on the item that plays (#505).
     */
    val program: Int? = null,
) {
    init {
        require(skipInitialBytes >= 0) { "skipInitialBytes must not be negative, was $skipInitialBytes" }
        require(maxBitrate == null || maxBitrate > 0) { "maxBitrate must be positive, was $maxBitrate" }
        require(maxVideoHeight == null || maxVideoHeight > 0) { "maxVideoHeight must be positive, was $maxVideoHeight" }
        require(variant == null || variant >= 0) { "variant must not be negative, was $variant" }
        require(program == null || program > 0) { "program must be positive, was $program" }
    }
}
