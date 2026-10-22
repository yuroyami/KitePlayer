package io.github.yuroyami.kiteplayer.compose

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.yuroyami.kiteplayer.StreamVariant
import io.github.yuroyami.kiteplayer.TrackInfo
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration

/**
 * How [KitePlayerControls] look: the colours and the sizes, in place of a theme. The defaults are
 * white controls over a dark veil, which read over any picture.
 */
@Immutable
public class KitePlayerControlsStyle(
    /** The icons and the text. */
    public val contentColor: Color = Color.White,
    /** A control that cannot act now, such as next at the end of a queue. */
    public val disabledColor: Color = Color.White.copy(alpha = 0.38f),
    /** The played part of the seek bar, its thumb, the volume and the mark of a chosen option. */
    public val accentColor: Color = Color.White,
    /** The buffered part of the seek bar. */
    public val bufferedColor: Color = Color.White.copy(alpha = 0.5f),
    /** The rest of the seek bar and of the volume. */
    public val trackColor: Color = Color.White.copy(alpha = 0.24f),
    /** The veil over the picture while the controls show. */
    public val scrimColor: Color = Color.Black.copy(alpha = 0.4f),
    /** The background of a menu and of the scrub preview. */
    public val menuColor: Color = Color(0xF0202124),
    /** The ring round the control that has the keyboard or D-pad focus. */
    public val focusColor: Color = Color(0xFF8AB4F8),
    /** Each button's touch target. */
    public val buttonSize: Dp = 48.dp,
    /** The play button's touch target. */
    public val playButtonSize: Dp = 64.dp,
    /** Each icon. */
    public val iconSize: Dp = 24.dp,
    /** The thickness of the seek bar's line. */
    public val seekBarThickness: Dp = 4.dp,
    /** The times, the menus and the scrub target. */
    public val textSize: TextUnit = 14.sp,
)

/**
 * Every word of [KitePlayerControls], in English by default. A screen reader reads the same
 * words, so an application that is not in English passes its own, the times and the track names
 * included, and the controls say nothing it did not give them.
 */
@Immutable
public class KitePlayerControlsLabels(
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
)

/** One instance for every default parameter, so a default never defeats Compose's skipping. */
internal val DefaultControlsLabels: KitePlayerControlsLabels = KitePlayerControlsLabels()
internal val DefaultControlsStyle: KitePlayerControlsStyle = KitePlayerControlsStyle()

/** `1:23`, or `1:02:03` once there are hours. Minutes are not padded, because a reader says "oh" for a zero. */
internal fun clockText(value: Duration): String {
    val total = value.inWholeSeconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    val paddedSeconds = if (seconds < 10) "0$seconds" else "$seconds"
    if (hours == 0L) return "$minutes:$paddedSeconds"
    val paddedMinutes = if (minutes < 10) "0$minutes" else "$minutes"
    return "$hours:$paddedMinutes:$paddedSeconds"
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
