package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecKind
import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.CodecId
import io.github.yuroyami.kiteffmpeg.HardwareAccel

/**
 * The Apple native axis is VideoToolbox (S2.b): an
 * HWACCEL behind the ordinary decoders, eligible for exactly the codecs whose hwaccels the
 * FFmpeg build carries. Whether THIS machine honours the attach is FFmpeg's runtime answer, and
 * a refusal is one more cause the measured fallback path already handles.
 */
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = codec.videoToolboxRoute())

/**
 * AV1 sits with h264 and hevc because the hwaccel is REAL in the build, not because the codec is
 * fashionable: `ff_av1_videotoolbox_hwaccel` is a defined symbol in the shipped `libavcodec.a`, so
 * FFmpeg's `av1` decoder can attach it. Leaving av1 out of this list meant the route was never asked
 * for, so the decoder opened with no hwaccel at all, and `av1dec.c` is a hwaccel shell that answers
 * ENOSYS (-78) in that state. That refusal is what the format matrix recorded before this line.
 *
 * The measured runs behind this comment were on a SIMULATOR hosted by an M2, which has no AV1
 * silicon, so they prove the refusal-and-fallback path and NOT that the attach succeeds. A named
 * simulator carries a phone's name and its host's hardware; the two must never be read as one.
 * Positive proof needs an A17 Pro / M3 or newer machine and is still owed.
 *
 * A device with no AV1 silicon (anything before A17 Pro / M3) refuses the attach instead, and that
 * refusal is one more cause the measured fallback already handles. On such a device the fallback
 * lands on FFmpeg's `av1` decoder, which is the same shell, so the open still fails: software AV1
 * needs vendored dav1d, which is its own job and is NOT closed by this route.
 *
 * vp9 stays out on purpose. The hwaccel symbol exists, but no Apple silicon carries a VP9 decode
 * block, so every attach would fail and pay for the attempt; FFmpeg's native VP9 decoder is real
 * software and already handles those files. prores and the mpeg-family hwaccels are the same shape
 * as AV1 and are eligible in principle, but no fixture exercises them yet, and this project does
 * not advertise a route it has never measured.
 */
private fun String.videoToolboxRoute(): HardwareRoute? = when (trim().lowercase()) {
    "h264", "avc1", "hevc", "h265", "hev1", "av1" ->
        HardwareRoute.Accel(HardwareAccel.VideoToolbox, HwdecKind.VideoToolbox)
    else -> null
}

/**
 * AudioToolbox, Apple's own audio decoders, for the four codecs where the offload is worth having.
 *
 * The iOS trees carried NONE of the `*_at` decoders until the parity audit found the cause (autodetect
 * is off, so an unrequested framework is simply absent) and the build started asking for AudioToolbox.
 * Compiling them in is not using them: FFmpeg resolves a codec id to its FIRST registered decoder,
 * which is always the native one, so a platform decoder only ever runs when it is named. This is
 * where it gets named.
 *
 * The list is short on purpose, and every omission has a reason rather than an oversight:
 *
 * - `aac`, `alac`, `ac3`, `eac3` are IN. These are the formats real files arrive in where the decode
 *   is heavy enough for the offload to show up on a battery, surround Dolby most of all.
 * - `mp1`/`mp2`/`mp3` are OUT. FFmpeg's own mpegaudio decoder is among the most optimised in the
 *   project and now has NEON behind it on iOS too; `mp3_at` would trade that for nothing measurable.
 * - `pcm_alaw`/`pcm_mulaw` are OUT. A table lookup does not need a framework.
 * - `amr_nb`, `gsm_ms`, `ilbc`, `qdm2`, `qdmc` are OUT. Speech and legacy formats no fixture covers,
 *   where the native and platform decoders differ in what they accept and nothing has measured which
 *   way that cuts.
 *
 * A refusal at open is ordinary and handled: the factory reopens on the native decoder and warns once.
 */
internal actual fun platformAudioDecoder(codec: String): CodecId? = when (codec.trim().lowercase()) {
    "aac" -> CodecId("aac_at")
    "alac" -> CodecId("alac_at")
    "ac3" -> CodecId("ac3_at")
    "eac3" -> CodecId("eac3_at")
    else -> null
}
