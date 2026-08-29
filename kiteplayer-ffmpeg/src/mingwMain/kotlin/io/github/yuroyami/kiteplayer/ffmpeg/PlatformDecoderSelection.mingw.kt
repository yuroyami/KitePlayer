package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.CodecId

/**
 * Windows offers no hardware route here, so every codec decodes in software.
 *
 * **The build DOES carry D3D11VA, and this comment used to say it did not** (PAR-1, corrected
 * 2026-08-25). `libavcodec.a` for mingw-x64 contains eighteen d3d11va, d3d11va2 and dxva2 hwaccels,
 * compiled because the mingw configure profile never passed `--disable-autodetect`. Decision W-D4
 * described a reduced profile and the binary quietly exceeded it.
 *
 * Refusing the route anyway is still correct, because COMPILED is not PLUMBED. A D3D11VA hwaccel
 * needs a hardware device context and a frame download path on the KiteFFmpeg side, and neither
 * exists; offering the route would make every open pay an attach that always fails and would report
 * a hardware decoder in diagnostics that never ran.
 *
 * The hwaccels are deliberately left in the binary rather than stripped: stripping means a recipe
 * change, which makes every baked Windows tree stale and costs a rebake and a binary release, to
 * delete code that Windows video output will want. Plumbing them is its own register row and
 * arrives here as one more route, with the measured fallback the Apple axis uses.
 */
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = null)

/**
 * Windows has Media Foundation audio decoders, but FFmpeg exposes no `*_mf` DECODER to name;
 * its mf wrappers are encoders.
 */
internal actual fun platformAudioDecoder(codec: String): CodecId? = null
