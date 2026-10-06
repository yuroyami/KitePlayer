package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.ScriptColorMatrix
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue

/**
 * The colour an ASS script's colours are matched to (#499): the video stream's own matrix and range,
 * with a matrix the stream left unstated guessed from [pictureHeight] as every player guesses it.
 * Null when there is nothing to match to: no picture, HDR video, where no standard says how SDR
 * subtitles meet it and libass leaves the colours to the player, and video that is not YCbCr through
 * a matrix a script header could name, such as RGB, YCgCo, constant-luminance BT.2020 and ICtCp.
 */
internal fun assColorTarget(video: PlayerStreamInfo?, pictureHeight: Int): ColorSpaceInfo? {
    if (video == null) return null
    val guessed = ColorSpaceInfo.guessFor(pictureHeight)
    val stated = video.colorSpace ?: guessed
    val color = if (stated.matrix == ColorMatrix.Unspecified) stated.copy(matrix = guessed.matrix) else stated
    if (color.isHdr) return null
    return when (color.matrix) {
        ColorMatrix.Bt601, ColorMatrix.Bt470bg, ColorMatrix.Smpte170m, ColorMatrix.Bt709, ColorMatrix.Fcc,
        ColorMatrix.Smpte240m, ColorMatrix.Bt2020Ncl,
        -> color
        ColorMatrix.Unspecified, ColorMatrix.YCgCo, ColorMatrix.Bt2020Cl, ColorMatrix.ICtCp, ColorMatrix.Identity,
        -> null
    }
}

/**
 * [cues] with the colours of every ASS cue matched to [target] through its script's header (#499),
 * as the libass driver's `kite_ass_color.h` matches them, to the same 8-bit steps. A null [target]
 * and a list with no ASS cue come back as they are, so the steady state allocates nothing. The
 * viewer's own style override is applied after this, and is never converted.
 */
internal fun matchAssColors(cues: List<SubtitleCue>, target: ColorSpaceInfo?): List<SubtitleCue> {
    if (target == null || cues.none { it is SubtitleCue.Text && it.layout.scriptColorMatrix != null }) return cues
    return cues.map { cue ->
        val header = (cue as? SubtitleCue.Text)?.layout?.scriptColorMatrix ?: return@map cue
        cue.copy(spans = cue.spans.map { span -> span.copy(style = span.style.matched(header, target)) })
    }
}

private fun CueStyle.matched(header: ScriptColorMatrix, target: ColorSpaceInfo): CueStyle = copy(
    primaryColor = matchAssColor(primaryColor, header, target),
    outlineColor = matchAssColor(outlineColor, header, target),
    shadowColor = matchAssColor(shadowColor, header, target),
    backgroundColor = matchAssColor(backgroundColor, header, target),
)

/** The red and blue weights of one matrix, and whether its range is full. */
private class AssMatrix(val kr: Double, val kb: Double, val full: Boolean)

private fun ScriptColorMatrix.asMatrix(): AssMatrix? = when (this) {
    ScriptColorMatrix.Default, ScriptColorMatrix.Bt601Tv -> AssMatrix(0.299, 0.114, full = false)
    ScriptColorMatrix.Bt601Pc -> AssMatrix(0.299, 0.114, full = true)
    ScriptColorMatrix.Bt709Tv -> AssMatrix(0.2126, 0.0722, full = false)
    ScriptColorMatrix.Bt709Pc -> AssMatrix(0.2126, 0.0722, full = true)
    ScriptColorMatrix.Smpte240mTv -> AssMatrix(0.212, 0.087, full = false)
    ScriptColorMatrix.Smpte240mPc -> AssMatrix(0.212, 0.087, full = true)
    ScriptColorMatrix.FccTv -> AssMatrix(0.30, 0.11, full = false)
    ScriptColorMatrix.FccPc -> AssMatrix(0.30, 0.11, full = true)
    ScriptColorMatrix.None, ScriptColorMatrix.Unknown -> null
}

private fun ColorSpaceInfo.asMatrix(): AssMatrix? = when (matrix) {
    ColorMatrix.Bt601, ColorMatrix.Bt470bg, ColorMatrix.Smpte170m -> AssMatrix(0.299, 0.114, fullRange)
    ColorMatrix.Bt709 -> AssMatrix(0.2126, 0.0722, fullRange)
    ColorMatrix.Fcc -> AssMatrix(0.30, 0.11, fullRange)
    ColorMatrix.Smpte240m -> AssMatrix(0.212, 0.087, fullRange)
    ColorMatrix.Bt2020Ncl -> AssMatrix(0.2627, 0.0593, fullRange)
    ColorMatrix.Unspecified, ColorMatrix.YCgCo, ColorMatrix.Bt2020Cl, ColorMatrix.ICtCp, ColorMatrix.Identity -> null
}

/** [value] rounded to the nearest whole step and held to 0..255. */
private fun colorStep(value: Double): Int = when {
    !(value > 0.0) -> 0
    value >= 255.0 -> 255
    else -> (value + 0.5).toInt()
}

/** One ARGB colour matched from [header] to [target]; the alpha passes through. */
internal fun matchAssColor(argb: Int, header: ScriptColorMatrix, target: ColorSpaceInfo): Int {
    val from = header.asMatrix() ?: return argb
    val to = target.asMatrix() ?: return argb
    if (from.kr == to.kr && from.kb == to.kb && from.full == to.full) return argb
    var r = ((argb shr 16) and 0xFF) / 255.0
    var g = ((argb shr 8) and 0xFF) / 255.0
    var b = (argb and 0xFF) / 255.0

    var y = from.kr * r + (1.0 - from.kr - from.kb) * g + from.kb * b
    var cb = (b - y) / (2.0 * (1.0 - from.kb))
    var cr = (r - y) / (2.0 * (1.0 - from.kr))
    val y8 = colorStep((if (from.full) 0.0 else 16.0) + (if (from.full) 255.0 else 219.0) * y)
    val cb8 = colorStep(128.0 + (if (from.full) 255.0 else 224.0) * cb)
    val cr8 = colorStep(128.0 + (if (from.full) 255.0 else 224.0) * cr)

    y = (y8 - (if (to.full) 0.0 else 16.0)) / (if (to.full) 255.0 else 219.0)
    cb = (cb8 - 128.0) / (if (to.full) 255.0 else 224.0)
    cr = (cr8 - 128.0) / (if (to.full) 255.0 else 224.0)
    r = y + 2.0 * (1.0 - to.kr) * cr
    b = y + 2.0 * (1.0 - to.kb) * cb
    g = (y - to.kr * r - to.kb * b) / (1.0 - to.kr - to.kb)
    return (argb and 0xFF000000.toInt()) or (colorStep(r * 255.0) shl 16) or (colorStep(g * 255.0) shl 8) or colorStep(b * 255.0)
}
