package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecMediaBackend
import io.github.yuroyami.kiteplayer.output.WebOutputBackend
import io.github.yuroyami.kitecodec.FFmpeg
import io.github.yuroyami.kitecodec.KiteCodecWeb

internal actual val platformKitePlayerDefaults: KitePlayerPlatformDefaults =
    WebKitePlayerPlatformDefaults

/**
 * The web stack (17.14 X-12): the FFmpeg backend over KiteCodec's wasm build, paired with the web
 * clock and the silent paced sink.
 *
 * Availability asks a question no other platform has to. Everywhere else the codec is linked into
 * the binary and `FFmpeg.identity` can be read at any time; here it is a separate wasm module the
 * page fetches, so this reports Unavailable with the fix in the message until
 * `KiteCodecWeb.load` or `attach` has completed. That is deliberately not lazy-cached: a consumer
 * may load the codec after first asking, and a cached "unavailable" would be wrong forever.
 */
private object WebKitePlayerPlatformDefaults : KitePlayerPlatformDefaults {

    override val availability: KitePlayerAvailability
        get() {
            if (!KiteCodecWeb.isLoaded) {
                return KitePlayerAvailability.Unavailable(
                    "The KiteCodec wasm module is not loaded. Call KiteCodecWeb.load(url), or " +
                        "attach(module) if a bundler rewrites your dynamic imports, and await it " +
                        "before creating a player.",
                )
            }
            return runCatching { FFmpeg.identity }.fold(
                onSuccess = { identity ->
                    if (identity.isAcceptable) {
                        KitePlayerAvailability.Available
                    } else {
                        KitePlayerAvailability.Unavailable(identity.provisioning)
                    }
                },
                onFailure = { failure ->
                    KitePlayerAvailability.Unavailable(
                        failure.message ?: "the KiteCodec wasm module could not be read",
                    )
                },
            )
        }

    /** No browser gives a page a picture-in-picture surface the player itself can drive. */
    override val supportsPictureInPicture: Boolean = false

    override fun backendsOrNull(): Backends? = if (availability.isAvailable) {
        Backends(backend = KiteCodecMediaBackend(), output = WebOutputBackend)
    } else {
        null
    }
}
