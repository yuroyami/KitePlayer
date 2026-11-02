package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.DecoderId

internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = codec.mediaCodecRoute())

private fun String.mediaCodecRoute(): HardwareRoute? = when (trim().lowercase()) {
    "h264", "avc1" -> HardwareRoute.NamedDecoder(DecoderId.H264MediaCodec, HwdecKind.MediaCodec)
    "hevc", "h265", "hev1" -> HardwareRoute.NamedDecoder(DecoderId.HevcMediaCodec, HwdecKind.MediaCodec)
    // KiteFFmpeg builds these three too. A live stream, which takes this route rather than the
    // view's own MediaCodec decoder, decoded them on the CPU without them (#643). A phone with no
    // such decoder falls back to dav1d or FFmpeg's own, as the route allows.
    "av1", "av01" -> HardwareRoute.NamedDecoder(DecoderId.Av1MediaCodec, HwdecKind.MediaCodec)
    "vp9", "vp09" -> HardwareRoute.NamedDecoder(DecoderId.Vp9MediaCodec, HwdecKind.MediaCodec)
    "vp8", "vp08" -> HardwareRoute.NamedDecoder(DecoderId.Vp8MediaCodec, HwdecKind.MediaCodec)
    else -> null
}

/**
 * MediaCodec has audio decoders, but FFmpeg's mediacodec wrapper is video only, so there is no
 * name to hand it. An Android audio offload would go through AudioTrack, not through this seam.
 */
internal actual fun platformAudioDecoder(codec: String): DecoderId? = null
