package io.github.yuroyami.kiteplayer

/**
 * What a Dolby Vision video stream is, as its container declares it.
 *
 * A Dolby Vision stream is an ordinary HEVC or AV1 stream, the base layer, plus a reference
 * processing unit (the RPU) on every frame that says how to turn the base layer into the picture
 * that was graded, and how bright each scene is. Whether the base layer is a picture of its own
 * decides what playing it costs, and [baseLayerPlaysAlone] answers that.
 */
public data class DolbyVisionInfo(
    /** The profile, such as 5, 7, 8 or 10. A profile written as 8.1 reports 8 here and 1 in [baseLayerCompatibility]. */
    val profile: Int,
    /** The level, which bounds the picture size and the frame rate. */
    val level: Int,
    /**
     * What the base layer is when shown without the RPU, as the configuration's signal
     * compatibility id: 0 nothing (profiles 5 and 10.0), 1 HDR10, 2 SDR in BT.709, 4 HLG and 6
     * HDR10 as an Ultra HD Blu-ray carries it.
     */
    val baseLayerCompatibility: Int,
    /**
     * Whether the stream carries an enhancement layer, as profile 7 does. Nothing here decodes
     * it, so such a stream plays its base layer, which is HDR10 of its own.
     */
    val hasEnhancementLayer: Boolean = false,
) {
    /**
     * True when the base layer is an HDR10, HLG or SDR picture of its own, which plays as any
     * other stream of that kind does. False for profile 5 and profile 10.0, whose base layer is
     * coded in Dolby's IPT colour space: the player composes each frame with its RPU into HDR10
     * before a renderer sees it, which costs processor time on every frame.
     */
    public val baseLayerPlaysAlone: Boolean get() = baseLayerCompatibility != 0

    /**
     * The profile as Dolby writes it: "5" and "7", and for the profiles that come in several
     * compatibilities the compatibility after a dot, "8.1", "8.4" or "10.0".
     */
    public val profileName: String
        get() = if (profile == 8 || profile == 10) "$profile.$baseLayerCompatibility" else "$profile"
}
