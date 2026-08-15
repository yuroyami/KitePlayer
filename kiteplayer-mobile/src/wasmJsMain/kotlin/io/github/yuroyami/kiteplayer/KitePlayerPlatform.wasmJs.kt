package io.github.yuroyami.kiteplayer

internal actual val platformKitePlayerDefaults: KitePlayerPlatformDefaults =
    UnavailableKitePlayerPlatformDefaults(
        "KitePlayer's Wasm media and output backends are not implemented yet",
    )
