package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.output.DesktopOutputBackend
import io.github.yuroyami.kiteffmpeg.FFmpeg
import java.awt.GraphicsEnvironment
import java.awt.Toolkit

internal actual val platformKitePlayerDefaults: KitePlayerPlatformDefaults =
    DesktopKitePlayerPlatformDefaults

/**
 * The desktop JVM stack: the FFmpeg backend over KiteFFmpeg's JNI adapter, paired with the
 * SourceDataLine sink and the AWT subtitle rasterizer.
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
                        failure.message ?: "the KiteFFmpeg JNI library could not be loaded",
                    )
                },
            )
    }

    /**
     * Whether `KitePlayerPictureInPicture` can open its floating window here: the JVM has a screen,
     * and its window system can keep a window above the others. It asks the same two questions as
     * that class's `createOrNull`, headless first, because a headless JVM has no screen at all.
     */
    override val supportsPictureInPicture: Boolean
        get() = !GraphicsEnvironment.isHeadless() &&
            runCatching { Toolkit.getDefaultToolkit().isAlwaysOnTopSupported }.getOrDefault(false)

    override fun backendsOrNull(): Backends? = if (availability.isAvailable) {
        Backends(backend = KiteFFmpegMediaBackend(), output = DesktopOutputBackend)
    } else {
        null
    }
}
