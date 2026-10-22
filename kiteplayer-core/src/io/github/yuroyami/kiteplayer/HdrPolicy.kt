package io.github.yuroyami.kiteplayer

/** How HDR video, with a PQ or HLG transfer, reaches the screen. */
public enum class HdrPolicy {
    /**
     * Shows HDR as HDR where the renderer and the display can, and tone maps it to standard range
     * elsewhere. The Metal renderer shows it on an Apple display with extended range, and the
     * Android Surface path hands it to the system, which shows it on an HDR display.
     */
    Auto,

    /** Tone maps HDR to standard range everywhere. */
    ToneMap,
}

/** What the screen shows of the video's dynamic range, as the renderer reports it. */
public enum class VideoDynamicRange {
    /** Standard range video, or no report yet. */
    Standard,

    /** HDR video, shown with the extra range of an HDR display. */
    High,

    /** HDR video, tone mapped to standard range by the renderer, the decoder or the system. */
    ToneMapped,
}
