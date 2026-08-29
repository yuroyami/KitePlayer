package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.HwdecPolicy
import io.github.yuroyami.kiteffmpeg.CodecId

/**
 * Software only, for now, and the "for now" is a registered item rather than a shrug.
 *
 * The wasm decoder has no hardware route: it is FFmpeg compiled to WebAssembly. The browser DOES
 * have one, `VideoDecoder` from WebCodecs, measured at 715 fps on 1080p h264 against 182 fps in
 * software, and that is X-15's subject. When it lands it belongs HERE, because this function is
 * where the engine asks what route a codec gets. Until then advertising no hardware is the honest
 * answer, and a route that claimed otherwise would make the fallback logic above choose wrongly.
 */
internal actual fun platformDecoderSelection(codec: String, policy: HwdecPolicy): DecoderSelection =
    decoderSelection(policy, route = null)

/**
 * The browser's audio decoder is WebCodecs `AudioDecoder`, which is not an FFmpeg decoder name.
 * When it lands it arrives as its own backend, not as a string handed to this one.
 */
internal actual fun platformAudioDecoder(codec: String): CodecId? = null
