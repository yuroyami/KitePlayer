package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.DecoderId

/**
 * Android native has no hardware route in this build, so every codec decodes in software.
 *
 * MediaCodec needs a Java VM, and a plain Android native binary has none. The Android target of
 * this module, which runs in an app's VM, has the MediaCodec route.
 */
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = null)

/** Android native has no platform audio decoder: MediaCodec is out of reach here, as above. */
internal actual fun platformAudioDecoder(codec: String): DecoderId? = null
