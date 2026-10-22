package io.github.yuroyami.kiteplayer

/** Linux native has the FFmpeg backend and no audio output, so the caller brings the output. */
internal actual val platformKitePlayerDefaults: KitePlayerPlatformDefaults =
    UnavailableKitePlayerPlatformDefaults(
        "KitePlayer has no audio output for Linux native, so there is no default player. " +
            "Pass KiteFFmpegMediaBackend() and your own OutputBackend to KitePlayer.create.",
    )
