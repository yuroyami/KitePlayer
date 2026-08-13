package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kitecodec.CodecId
import io.github.yuroyami.kitecodec.HardwareAccel

/**
 * How a platform reaches its hardware decoder, because FFmpeg has two shapes and they open
 * differently (S2.b). A [NamedDecoder] IS the hardware path under its own decoder name
 * (`h264_mediacodec`); an [Accel] is an HWACCEL attached behind the ordinary decoder before open
 * (VideoToolbox). The policy table below cares only about [kind]; the factory cares which shape
 * it must hand to KiteCodec.
 */
internal sealed class HardwareRoute {
    abstract val kind: HwdecKind

    internal data class NamedDecoder(
        val decoder: CodecId,
        override val kind: HwdecKind,
    ) : HardwareRoute()

    internal data class Accel(
        val accel: HardwareAccel,
        override val kind: HwdecKind,
    ) : HardwareRoute()
}

/** The exact hardware route to open, if any, and the recovery contract attached to that choice. */
internal data class DecoderSelection(
    val hardware: HardwareRoute?,
    val mayFallback: Boolean,
    val requiresHardware: Boolean,
)

/** Resolves [policy] against the hardware routes the current platform can actually open. */
internal expect fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection

/**
 * The policy table shared by the platform actuals and its exhaustive common test.
 *
 * [route] is null when the codec is ineligible on this platform. A required but ineligible
 * request stays distinguishable from an ordinary software choice through
 * [DecoderSelection.requiresHardware].
 */
internal fun decoderSelection(
    policy: HwdecPolicy,
    route: HardwareRoute?,
): DecoderSelection = when (policy) {
    HwdecPolicy.Off -> DecoderSelection(null, mayFallback = false, requiresHardware = false)
    HwdecPolicy.Auto -> if (route == null) {
        DecoderSelection(null, mayFallback = false, requiresHardware = false)
    } else {
        DecoderSelection(route, mayFallback = true, requiresHardware = false)
    }
    HwdecPolicy.Require -> DecoderSelection(
        hardware = route,
        mayFallback = false,
        requiresHardware = true,
    )
    is HwdecPolicy.Prefer -> if (route != null && route.kind in policy.order) {
        DecoderSelection(route, mayFallback = true, requiresHardware = false)
    } else {
        DecoderSelection(null, mayFallback = false, requiresHardware = false)
    }
}
