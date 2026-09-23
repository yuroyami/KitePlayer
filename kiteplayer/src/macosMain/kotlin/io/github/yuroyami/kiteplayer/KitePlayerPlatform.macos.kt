package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteffmpeg.FFmpeg
import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend

internal actual val platformKitePlayerDefaults: KitePlayerPlatformDefaults =
    MacosKitePlayerPlatformDefaults

/**
 * The macOS native stack: the FFmpeg backend paired with the CoreAudio sink and the CoreText
 * subtitle rasterizer.
 *
 * Availability is FFmpeg's own identity gate, the same question the backend asks at the first open.
 * No video renderer comes with it: the application owns the window, so it attaches a renderer from
 * `kiteplayer-output` to the player.
 */
private object MacosKitePlayerPlatformDefaults : KitePlayerPlatformDefaults {
    override val availability: KitePlayerAvailability by lazy {
        val identity = FFmpeg.identity
        if (identity.isAcceptable) {
            KitePlayerAvailability.Available
        } else {
            KitePlayerAvailability.Unavailable(identity.provisioning)
        }
    }

    /** The player drives no system picture-in-picture window on macOS. */
    override val supportsPictureInPicture: Boolean = false

    override fun backendsOrNull(): Backends? = if (availability.isAvailable) {
        Backends(backend = KiteFFmpegMediaBackend(), output = AppleOutputBackend)
    } else {
        null
    }
}
