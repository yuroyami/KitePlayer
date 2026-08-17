package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kitecodec.CodecId

internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = codec.mediaCodecRoute())

private fun String.mediaCodecRoute(): HardwareRoute? = when (trim().lowercase()) {
    "h264", "avc1" -> HardwareRoute.NamedDecoder(CodecId("h264_mediacodec"), HwdecKind.MediaCodec)
    "hevc", "h265", "hev1" -> HardwareRoute.NamedDecoder(CodecId("hevc_mediacodec"), HwdecKind.MediaCodec)
    else -> null
}

/**
 * MediaCodec has audio decoders, but FFmpeg's mediacodec wrapper is video only, so there is no
 * name to hand it. An Android audio offload would go through AudioTrack, not through this seam.
 */
internal actual fun platformAudioDecoder(codec: String): CodecId? = null
