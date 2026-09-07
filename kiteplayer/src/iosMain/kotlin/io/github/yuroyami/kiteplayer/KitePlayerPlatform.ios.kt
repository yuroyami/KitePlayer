package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.ffmpeg.KiteFFmpegMediaBackend
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend
import platform.AVKit.AVPictureInPictureController

internal actual val platformKitePlayerDefaults: KitePlayerPlatformDefaults =
    IosKitePlayerPlatformDefaults

private object IosKitePlayerPlatformDefaults : KitePlayerPlatformDefaults {
    override val availability: KitePlayerAvailability = KitePlayerAvailability.Available
    /**
     * The system's own answer, which needs no context on iOS and so can be asked here.
     *
     * It is false on devices that do not offer the feature at all. Showing the player in the small
     * window also needs the sample buffer renderer, because that layer is the only content source
     * a controller accepts from a player that is not the system player.
     */
    override val supportsPictureInPicture: Boolean
        get() = AVPictureInPictureController.isPictureInPictureSupported()


    override fun backendsOrNull(): Backends = Backends(
        backend = KiteFFmpegMediaBackend(),
        output = AppleOutputBackend,
    )
}
