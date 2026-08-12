package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kitecodec.CodecId

internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, hardwareDecoder = codec.mediaCodecDecoder())

private fun String.mediaCodecDecoder(): CodecId? = when (trim().lowercase()) {
    "h264", "avc1" -> CodecId("h264_mediacodec")
    "hevc", "h265", "hev1" -> CodecId("hevc_mediacodec")
    else -> null
}
