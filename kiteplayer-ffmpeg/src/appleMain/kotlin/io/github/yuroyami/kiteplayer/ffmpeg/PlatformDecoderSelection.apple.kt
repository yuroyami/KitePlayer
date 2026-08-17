package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kitecodec.HardwareAccel

/**
 * The Apple native axis is VideoToolbox (S2.b): an
 * HWACCEL behind the ordinary decoders, eligible for exactly the codecs whose hwaccels the
 * FFmpeg build carries. Whether THIS machine honours the attach is FFmpeg's runtime answer, and
 * a refusal is one more cause the measured fallback path already handles.
 */
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = codec.videoToolboxRoute())

private fun String.videoToolboxRoute(): HardwareRoute? = when (trim().lowercase()) {
    "h264", "avc1", "hevc", "h265", "hev1" ->
        HardwareRoute.Accel(HardwareAccel.VideoToolbox, HwdecKind.VideoToolbox)
    else -> null
}
