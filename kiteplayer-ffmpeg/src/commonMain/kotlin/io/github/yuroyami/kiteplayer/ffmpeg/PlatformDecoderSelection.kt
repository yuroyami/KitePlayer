package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.DecoderId
import io.github.yuroyami.kiteffmpeg.HardwareAccel

/**
 * How a platform reaches its hardware decoder, because FFmpeg has two shapes and they open
 * differently. A [NamedDecoder] IS the hardware path under its own decoder name
 * (`h264_mediacodec`); an [Accel] is an HWACCEL attached behind the ordinary decoder before open
 * (VideoToolbox). The policy table below cares only about [kind]; the factory cares which shape
 * it must hand to KiteFFmpeg.
 */
internal sealed class HardwareRoute {
    abstract val kind: HwdecKind

    internal data class NamedDecoder(
        val decoder: DecoderId,
        override val kind: HwdecKind,
    ) : HardwareRoute()

    internal data class Accel(
        val accel: HardwareAccel,
        override val kind: HwdecKind,
        /**
         * The decoder to attach [accel] to, by name, or null for the one FFmpeg finds first for
         * the codec. Needed when that first one cannot take the attach, as dav1d cannot for AV1.
         */
        val decoder: DecoderId? = null,
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
 * The platform's own audio decoder for [codec], by FFmpeg decoder name, or null to decode in software.
 *
 * Audio gets a name and no [HardwareRoute], because the two shapes FFmpeg offers for video do not
 * both exist here: AudioToolbox is a NAMED decoder (`aac_at`) and never an hwaccel. There is also no
 * [HwdecPolicy] argument. `HwdecPolicy` describes what the VIDEO path may do, its `Require` means
 * "refuse rather than decode this picture in software", and applying that to audio would refuse every
 * file whose codec has no platform twin. Audio's contract is softer by nature: prefer the platform
 * decoder, fall back silently-but-warned at open, never refuse.
 *
 * [codec] is FFmpeg's decoder short name, the same string the video table matches on.
 */
internal expect fun platformAudioDecoder(codec: String): DecoderId?

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

/**
 * The codecs VideoToolbox decodes, for the Apple native targets and for the desktop JVM on macOS.
 * Both link an FFmpeg that carries the same VideoToolbox hwaccels.
 *
 * AV1 sits with h264 and hevc because the hwaccel is REAL in the build, not because the codec is
 * fashionable: `ff_av1_videotoolbox_hwaccel` is a defined symbol in the shipped `libavcodec.a`, so
 * FFmpeg's `av1` decoder can attach it. Leaving av1 out of this list meant the route was never asked
 * for, so the decoder opened with no hwaccel at all, and `av1dec.c` is a hwaccel shell that answers
 * ENOSYS (-78) in that state.
 *
 * The AV1 route names that decoder, because it is not the one FFmpeg finds first. The build also
 * carries dav1d, which comes first for the codec and cannot take the attach, so an attach by codec
 * decoded in software while the status said VideoToolbox (#95). A device with no AV1 silicon
 * (anything before A17 Pro / M3) refuses the attach at the first packet, and the measured fallback
 * then decodes with dav1d.
 *
 * The measured runs behind this comment were on an M2, which has no AV1 silicon, so they prove the
 * refusal-and-fallback path and NOT that the attach succeeds. A named simulator carries a phone's
 * name and its host's hardware; the two must never be read as one. Positive proof needs an
 * A17 Pro / M3 or newer machine and is still owed.
 *
 * vp9 stays out on purpose. The hwaccel symbol exists, but no Apple silicon carries a VP9 decode
 * block, so every attach would fail and pay for the attempt; FFmpeg's native VP9 decoder is real
 * software and already handles those files. prores and the mpeg-family hwaccels are the same shape
 * as AV1 and are eligible in principle, but no fixture exercises them yet, and this project does
 * not advertise a route it has never measured.
 */
internal fun String.videoToolboxRoute(): HardwareRoute? = when (trim().lowercase()) {
    "h264", "avc1", "hevc", "h265", "hev1" ->
        HardwareRoute.Accel(HardwareAccel.VideoToolbox, HwdecKind.VideoToolbox)
    "av1" -> HardwareRoute.Accel(HardwareAccel.VideoToolbox, HwdecKind.VideoToolbox, decoder = DecoderId("av1"))
    else -> null
}

/**
 * The codecs Direct3D 11 decodes, for the native Windows target and for the desktop JVM on Windows
 * (#101). KiteFFmpeg's Windows builds attach a Direct3D 11 device behind exactly these ordinary
 * decoders and no others, so the table names no codec the library cannot attach. Whether this
 * machine's GPU decodes the stream is FFmpeg's runtime answer: a machine with no usable device
 * refuses at open, a stream the GPU cannot take comes back as software frames, and the measured
 * fallback handles both, as it does for VideoToolbox.
 *
 * AV1 stays out although FFmpeg has a D3D11VA AV1 hwaccel: the library does not offer it behind
 * `av1`, and FFmpeg finds dav1d first for that codec, which cannot take the attach.
 */
internal fun String.d3d11vaRoute(): HardwareRoute? = when (trim().lowercase()) {
    "h264", "avc1", "hevc", "h265", "hev1", "vp9", "mpeg2video", "vc1", "wmv3" ->
        HardwareRoute.Accel(HardwareAccel.D3d11va, HwdecKind.D3d11va)
    else -> null
}
