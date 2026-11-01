package io.github.yuroyami.kiteplayer.view

import io.github.yuroyami.kiteplayer.KiteLog

/**
 * Says once, through [KiteLog], that [view] has a player and no renderer factory, so it shows no
 * picture (#601). The sound plays on, and nothing else would say why the picture is missing.
 * Confined to the view's own thread.
 */
internal class MissingRendererNotice(private val view: String) {
    private var told = false

    fun note() {
        if (told) return
        told = true
        KiteLog.log(
            "kiteplayer-view",
            "$view has a player and no renderer factory, so it shows no picture. Set its rendererFactory, " +
                "or build the player with KitePlayer(config) and the FFmpeg backend, which sets " +
                "PlayerViewDefaults.rendererFactory.",
        )
    }
}
