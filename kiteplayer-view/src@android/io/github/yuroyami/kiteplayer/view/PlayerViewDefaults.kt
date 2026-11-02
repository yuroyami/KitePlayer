package io.github.yuroyami.kiteplayer.view

import kotlin.concurrent.Volatile

/**
 * What a [KitePlayerView] uses when it has no [KitePlayerView.rendererFactory] of its own.
 *
 * The `kiteplayer` module's `KitePlayer(config)` sets [rendererFactory] when it builds a player on
 * the FFmpeg media backend, the default one or one the app built itself, so a view shows that
 * player's picture after `view.player = player` alone. `KitePlayer.create` sets nothing, so a view
 * showing a player built there needs a factory of its own, or this one set by the app; a view with
 * neither logs that it shows no picture, once (#601). An app with a renderer of its own may set it
 * as well. A factory set on the view itself always wins.
 */
public object PlayerViewDefaults {
    /** The factory that a view with none of its own uses, or null for no default. */
    @Volatile
    public var rendererFactory: AndroidPlayerViewRendererFactory? = null
}
