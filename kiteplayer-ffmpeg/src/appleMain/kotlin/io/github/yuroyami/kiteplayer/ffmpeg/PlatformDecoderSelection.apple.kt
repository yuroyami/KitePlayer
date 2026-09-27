package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.DecoderId

/**
 * The Apple native axis is VideoToolbox: an
 * HWACCEL behind the ordinary decoders, eligible for exactly the codecs whose hwaccels the
 * FFmpeg build carries. Whether THIS machine honours the attach is FFmpeg's runtime answer, and
 * a refusal is one more cause the measured fallback path already handles.
 */
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = codec.videoToolboxRoute())

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
internal actual fun platformAudioDecoder(codec: String): DecoderId? = when (codec.trim().lowercase()) {
    "aac" -> DecoderId("aac_at")
    "alac" -> DecoderId("alac_at")
    "ac3" -> DecoderId("ac3_at")
    "eac3" -> DecoderId("eac3_at")
    else -> null
}
