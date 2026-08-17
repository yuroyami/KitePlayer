package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy

/**
 * Windows has no hardware route in this build, so every codec decodes in software.
 *
 * This is honest, not a placeholder: D-2 allows hardware acceleration only INSIDE FFmpeg, and the
 * reduced desktop profile of KPKMP.md 17.13 (decision W-D4) compiles no D3D11VA hwaccel.
 * Claiming a route the build cannot honour would make every open pay an attach that always fails,
 * and would report a hardware decoder in diagnostics that never ran. When D3D11VA lands
 * it arrives here, as one more route, with the same measured fallback the Apple axis uses.
 */
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = null)
