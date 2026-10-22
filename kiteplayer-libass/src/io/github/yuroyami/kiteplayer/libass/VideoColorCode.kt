package io.github.yuroyami.kiteplayer.libass

import io.github.yuroyami.kiteplayer.spi.ColorMatrix
import io.github.yuroyami.kiteplayer.spi.TypesetFrame

/**
 * [TypesetFrame.videoColor]'s matrix as the C driver's `kite_ass_color.h` names it (#499): 1 BT.601,
 * 2 BT.709, 3 FCC, 4 SMPTE 240M, 5 BT.2020, or 0 for nothing to match to. The engine gives no colour
 * for HDR or RGB video, and a matrix no script header can have passed through matches nothing here.
 */
internal val TypesetFrame.videoMatrixCode: Int
    get() = when (videoColor?.matrix) {
        ColorMatrix.Bt601, ColorMatrix.Bt470bg, ColorMatrix.Smpte170m -> 1
        ColorMatrix.Bt709 -> 2
        ColorMatrix.Fcc -> 3
        ColorMatrix.Smpte240m -> 4
        ColorMatrix.Bt2020Ncl -> 5
        ColorMatrix.Unspecified, ColorMatrix.YCgCo, ColorMatrix.Bt2020Cl, ColorMatrix.ICtCp,
        ColorMatrix.Identity, null,
        -> 0
    }

/** Whether [TypesetFrame.videoColor] is full range. */
internal val TypesetFrame.videoFullRange: Boolean get() = videoColor?.fullRange == true
