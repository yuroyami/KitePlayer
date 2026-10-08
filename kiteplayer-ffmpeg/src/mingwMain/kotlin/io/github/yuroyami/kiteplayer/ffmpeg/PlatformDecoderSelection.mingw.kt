package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.DecoderId

/**
 * Windows attaches Direct3D 11 behind the ordinary decoders that KiteFFmpeg's Windows build
 * offers it for (#101). The library creates the device and downloads each frame to main memory on
 * request, so a frame reaches any renderer that reads planes. A machine without a usable device
 * refuses at open, and the measured fallback then decodes in software with a warning, as it does
 * for VideoToolbox. Whether the GPU route is faster has to be measured on a Windows PC with a GPU;
 * nothing here has run one yet.
 */
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = codec.d3d11vaRoute())

/**
 * Windows has Media Foundation audio decoders, but FFmpeg exposes no `*_mf` DECODER to name;
 * its mf wrappers are encoders.
 */
internal actual fun platformAudioDecoder(codec: String): DecoderId? = null
