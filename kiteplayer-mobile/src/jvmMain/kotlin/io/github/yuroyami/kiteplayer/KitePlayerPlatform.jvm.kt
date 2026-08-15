package io.github.yuroyami.kiteplayer

internal actual val platformKitePlayerDefaults: KitePlayerPlatformDefaults =
    UnavailableKitePlayerPlatformDefaults(
        "KitePlayer's Desktop JVM media and output backends are not implemented yet",
    )
