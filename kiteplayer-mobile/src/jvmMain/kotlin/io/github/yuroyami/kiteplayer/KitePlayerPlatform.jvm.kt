package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecMediaBackend
import io.github.yuroyami.kiteplayer.output.DesktopOutputBackend
import io.github.yuroyami.kitecodec.FFmpeg

internal actual val platformKitePlayerDefaults: KitePlayerPlatformDefaults =
    DesktopKitePlayerPlatformDefaults

/**
 * The desktop JVM stack: the FFmpeg backend over KiteCodec's JNI adapter, paired with the
 * SourceDataLine sink and the AWT subtitle rasterizer (phase W).
 *
 * Availability is answered by FFmpeg's own identity gate rather than by looking for a file. That
 * is the same question the backend will ask at the first open, so a consumer never gets an
 * "available" answer followed by a load failure.
 */
private object DesktopKitePlayerPlatformDefaults : KitePlayerPlatformDefaults {
    override val availability: KitePlayerAvailability by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { FFmpeg.identity }
            .fold(
                onSuccess = { identity ->
                    if (identity.isAcceptable) {
                        KitePlayerAvailability.Available
                    } else {
                        KitePlayerAvailability.Unavailable(identity.provisioning)
                    }
                },
                onFailure = { failure ->
                    KitePlayerAvailability.Unavailable(
                        failure.message ?: "the KiteCodec JNI library could not be loaded",
                    )
                },
            )
    }

    /** No desktop window manager here offers a system picture-in-picture the player can drive. */
    override val supportsPictureInPicture: Boolean = false

    override fun backendsOrNull(): Backends? = if (availability.isAvailable) {
        Backends(backend = KiteCodecMediaBackend(), output = DesktopOutputBackend)
    } else {
        null
    }
}
