package io.github.yuroyami.kiteplayer.view

import kotlin.concurrent.Volatile

/**
 * What a [KitePlayerUIView] uses when it has no [KitePlayerUIView.rendererFactory] of its own.
 *
 * The `kiteplayer` module sets [rendererFactory] when it builds a player on the default stack, so
 * a view shows that player's picture after `view.player = player` alone. An app with a renderer of
 * its own may set it as well. A factory set on the view itself always wins.
 */
public object PlayerViewDefaults {
    /** The factory that a view with none of its own uses, or null for no default. */
    @Volatile
    public var rendererFactory: ApplePlayerViewRendererFactory? = null
}
