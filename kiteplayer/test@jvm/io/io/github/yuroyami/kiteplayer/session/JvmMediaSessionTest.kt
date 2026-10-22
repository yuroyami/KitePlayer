package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.availability
import io.github.yuroyami.kiteplayer.isAvailable
import io.github.yuroyami.kiteplayer.requireTestMedia
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The desktop stub. It exists so the same consumer code compiles here, and it must never pretend. */
class JvmMediaSessionTest {

    @Test
    fun `the desktop session says it is unavailable and holds no token`() = runBlocking {
        requireTestMedia(KitePlayer.isAvailable, "no desktop player: ${KitePlayer.availability}")
        val player = KitePlayer()
        try {
            KitePlayerMediaSession(player).use { session ->
                assertFalse(session.isAvailable)
                assertNull(session.platformToken)
                session.setArtwork(null)
            }
        } finally {
            player.closeAndAwait()
        }
    }
}
