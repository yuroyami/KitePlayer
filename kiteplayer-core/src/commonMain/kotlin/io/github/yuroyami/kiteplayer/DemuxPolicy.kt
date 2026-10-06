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
 * What an adaptive stream's picture is drawn into, which the player's choice of variant follows.
 * See [DemuxPolicy.fit].
 */
public data class VariantFit(
    /** How wide the area the picture is drawn into is, in physical pixels, or null for no cap. */
    val drawnWidth: Int? = null,
    /** How tall the area the picture is drawn into is, in physical pixels, or null for no cap. */
    val drawnHeight: Int? = null,
    /** True when the output shows HDR as HDR, so an HDR variant is preferred over an SDR one. */
    val showsHdr: Boolean = false,
) {
    init {
        require(drawnWidth == null || drawnWidth > 0) { "drawnWidth must be positive, was $drawnWidth" }
        require(drawnHeight == null || drawnHeight > 0) { "drawnHeight must be positive, was $drawnHeight" }
    }

    /**
     * The most pixels a variant may have under this fit, among variants of [sizes], or null for no
     * cap. Each size is fitted into the drawn area at its own shape, as the picture is shown, and
     * the cap is the smallest size that is not scaled up to fill it, so the picture is never
     * enlarged and nothing much larger than the screen is fetched. A 1920 by 1080 area of a 720p,
     * 1440p and 2160p ladder caps at 1440p, a landscape picture in a portrait phone's 1080 by 2400
     * area is capped by the width, and an area larger than every size caps nothing. Null as well
     * when either side of the area is unknown. The source's first choice and the player's later
     * steps both read it.
     */
    public fun pixelCap(sizes: Collection<VideoSize>): Long? {
        val areaWidth = drawnWidth ?: return null
        val areaHeight = drawnHeight ?: return null
        return sizes.filter { it.width > 0 && it.height > 0 }.filter { size ->
            val scale = minOf(areaWidth.toDouble() / size.width, areaHeight.toDouble() / size.height)
            // A size within two percent of its shown size counts as filling it, as an encoder's
            // rounding to a multiple of 16 would otherwise push the cap a rung up.
            scale <= 1.0 / 0.98
        }.minOfOrNull { it.width.toLong() * it.height }
    }
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
    /**
     * What the picture is drawn into, which the player's own choice of variant follows (#447): HDR
     * over SDR when the output shows HDR, and no variant larger than the smallest one that fills the
     * drawn area without being scaled up. [maxBitrate] and [maxVideoHeight] still apply on top. Null, the
     * default, has the player fill it at each open, and at each step up, from the renderer it
     * draws into; with no renderer there is no cap and SDR is preferred. Set it to choose for the
     * player, for example to plan for full screen before the view grows. `VariantFit()` keeps no
     * cap and prefers SDR.
     */
    val fit: VariantFit? = null,
) {
    init {
        require(skipInitialBytes >= 0) { "skipInitialBytes must not be negative, was $skipInitialBytes" }
        require(maxBitrate == null || maxBitrate > 0) { "maxBitrate must be positive, was $maxBitrate" }
        require(maxVideoHeight == null || maxVideoHeight > 0) { "maxVideoHeight must be positive, was $maxVideoHeight" }
        require(variant == null || variant >= 0) { "variant must not be negative, was $variant" }
        require(program == null || program > 0) { "program must be positive, was $program" }
    }
}
