package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kitecodec.CodecId

/** The exact decoder name to open and the recovery contract attached to that choice. */
internal data class DecoderSelection(
    val hardwareDecoder: CodecId?,
    val mayFallback: Boolean,
    val requiresHardware: Boolean,
)

/** Resolves [policy] against the hardware decoder names the current platform can actually open. */
internal expect fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection

/**
 * The policy table shared by the platform actuals and its exhaustive common test.
 *
 * [hardwareDecoder] is null when the codec is ineligible on this platform. A required but ineligible
 * request stays distinguishable from an ordinary software choice through [DecoderSelection.requiresHardware].
 */
internal fun decoderSelection(
    policy: HwdecPolicy,
    hardwareDecoder: CodecId?,
): DecoderSelection = when (policy) {
    HwdecPolicy.Off -> DecoderSelection(null, mayFallback = false, requiresHardware = false)
    HwdecPolicy.Auto -> if (hardwareDecoder == null) {
        DecoderSelection(null, mayFallback = false, requiresHardware = false)
    } else {
        DecoderSelection(hardwareDecoder, mayFallback = true, requiresHardware = false)
    }
    HwdecPolicy.Require -> DecoderSelection(
        hardwareDecoder = hardwareDecoder,
        mayFallback = false,
        requiresHardware = true,
    )
    is HwdecPolicy.Prefer -> if (
        hardwareDecoder != null && HwdecKind.MediaCodec in policy.order
    ) {
        DecoderSelection(hardwareDecoder, mayFallback = true, requiresHardware = false)
    } else {
        DecoderSelection(null, mayFallback = false, requiresHardware = false)
    }
}
