package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.ColorSpaceInfo
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo

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
