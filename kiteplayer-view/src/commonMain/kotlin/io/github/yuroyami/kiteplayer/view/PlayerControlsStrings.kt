package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.StreamVariant
import io.github.yuroyami.kiteplayer.TrackInfo
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration

/**
 * Every word of the default controls of a native view, in English by default. A screen reader reads
 * the same words, so an application that is not in English passes its own, the times and the track
 * names included.
 *
 * The names and the defaults are those of `KitePlayerControlsLabels` in `kiteplayer-compose-ui`,
 * so one translation serves the Compose controls and the native ones.
 */
public class PlayerControlsStrings(
    public val play: String = "Play",
    public val pause: String = "Pause",
    public val previous: String = "Previous",
    public val next: String = "Next",
    public val seekBar: String = "Seek",
    public val volume: String = "Volume",
    public val mute: String = "Mute",
    public val unmute: String = "Unmute",
    public val audio: String = "Audio",
    public val subtitles: String = "Subtitles",
    public val quality: String = "Quality",
    public val speed: String = "Speed",
    public val fullScreen: String = "Full screen",
    public val pictureInPicture: String = "Picture in picture",
    /** What the picture does when tapped while the controls are hidden. */
    public val showControls: String = "Show controls",
    /** What the picture does when tapped while the controls show. */
    public val hideControls: String = "Hide controls",
    /** The first option of the subtitle menu. */
    public val subtitlesOff: String = "Off",
    /** The first option of the quality menu, where the player chooses by itself. */
    public val automaticQuality: String = "Automatic",
    /** A time on screen, `1:23` or `1:02:03` by default. */
    public val time: (Duration) -> String = ::clockText,
    /**
     * What a screen reader says the seek bar stands at, from the position and the duration, which is
     * null for a live item. `1:23 of 4:56` by default.
     */
    public val positionOf: (Duration, Duration?) -> String = ::positionText,
    /** What a screen reader says the volume stands at, from 0 to 1. `50 percent` by default. */
    public val volumeLevel: (Float) -> String = { "${(it * 100).roundToInt()} percent" },
    /** A track in the audio and subtitle menus. [TrackInfo.label] by default: its title and language. */
    public val trackName: (TrackInfo) -> String = { it.label },
    /** A quality in the quality menu. Its height, `720p`, by default, else its bitrate. */
    public val variantName: (StreamVariant) -> String = ::variantText,
    /** A speed in the speed menu. `Normal` for 1 by default, else the factor, `1.5x`. */
    public val speedName: (Double) -> String = ::speedText,
) {
    public companion object {
        /** The English words, one instance for every default parameter. */
        public val Default: PlayerControlsStrings = PlayerControlsStrings()
    }
}

private fun positionText(position: Duration, duration: Duration?): String =
    if (duration == null) clockText(position) else "${clockText(position)} of ${clockText(duration)}"

private fun variantText(variant: StreamVariant): String {
    variant.height?.let { return "${it}p" }
    val kilobits = variant.bitrate / 1000
    if (kilobits < 1000) return "$kilobits kbps"
    return "${decimalText(variant.bitrate / 1_000_000.0)} Mbps"
}

private fun speedText(speed: Double): String = if (speed == 1.0) "Normal" else "${decimalText(speed)}x"

/** Up to two decimals, without trailing zeros: 1.5, 0.75, 2. */
private fun decimalText(value: Double): String {
    val hundredths = (value * 100).roundToLong()
    val whole = hundredths / 100
    val rest = hundredths % 100
    return when {
        rest == 0L -> "$whole"
        rest % 10 == 0L -> "$whole.${rest / 10}"
        else -> "$whole.${if (rest < 10) "0$rest" else "$rest"}"
    }
}
