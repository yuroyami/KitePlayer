package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecMediaBackend
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend

internal actual val platformKitePlayerDefaults: KitePlayerPlatformDefaults =
    IosKitePlayerPlatformDefaults

private object IosKitePlayerPlatformDefaults : KitePlayerPlatformDefaults {
    override val availability: KitePlayerAvailability = KitePlayerAvailability.Available
    override val supportsPictureInPicture: Boolean = false

    override fun backendsOrNull(): Backends = Backends(
        backend = KiteCodecMediaBackend(),
        output = AppleOutputBackend,
    )
}
