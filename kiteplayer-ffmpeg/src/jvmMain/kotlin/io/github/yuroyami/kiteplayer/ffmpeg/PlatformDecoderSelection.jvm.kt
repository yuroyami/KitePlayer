package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kitecodec.CodecId

// Public JVM is an unavailable placeholder until the desktop backend exists. Keep its selection
// software-shaped so it advertises no hardware route if lower-level code is inspected directly;
// opening media still refuses first with KiteCodec's typed Unsupported error.
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = null)

/**
 * Public JVM is a placeholder until the desktop backend exists; it opens nothing to accelerate.
 */
internal actual fun platformAudioDecoder(codec: String): CodecId? = null
