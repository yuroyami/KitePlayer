package io.github.yuroyami.kiteplayer.phone

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.ffmpeg.KiteCodecMediaBackend
import io.github.yuroyami.kiteplayer.output.AppleOutputBackend

public actual fun phoneBackends(): Backends = Backends(
    backend = KiteCodecMediaBackend(),
    output = AppleOutputBackend,
)
