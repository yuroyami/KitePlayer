package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import java.awt.Image

/**
 * The desktop has no media session. This exists so code that builds one on every platform compiles
 * here too; it mirrors nothing, and [isAvailable] is false.
 *
 * Each desktop system has its own API for this: a D-Bus interface on Linux, the system media
 * controls on Windows, the now playing centre on macOS. Every one of them needs a native bridge,
 * which is out by decision, so no module exists for one.
 */
public class KitePlayerMediaSession(
    @Suppress("UNUSED_PARAMETER") player: KitePlayer,
) : AutoCloseable {

    /** Always false here. */
    public val isAvailable: Boolean = false

    /** Nothing to hand out. Always null. */
    public val platformToken: Any? = null

    /** Accepted and dropped, so a caller keeps one line per platform. */
    public fun setArtwork(@Suppress("UNUSED_PARAMETER") image: Image?) {}

    override fun close() {}
}
