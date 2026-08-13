package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy

// Desktop JVM stays software for now. On macOS the JNI bridge's VideoToolbox rows are proven to
// work, but the desktop rendering paths mature in S3; wiring the selection there is measured
// work, not promised here (17.4.8 S2.d records the opportunity).
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = null)
