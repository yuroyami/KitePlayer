package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.availability
import io.github.yuroyami.kiteplayer.isAvailable
import io.github.yuroyami.kiteplayer.requireTestMedia
import kotlin.test.Test
import kotlin.test.assertFalse

/** The desktop has no system media session, but shared code still gets one call per platform. */
class AttachMediaSessionJvmTest {

    @Test
    fun theDesktopSessionIsTheHonestEmptyOne() {
        requireTestMedia(KitePlayer.isAvailable, "no desktop player: ${KitePlayer.availability}")
        KitePlayer().use { player ->
            player.attachMediaSession().use { session ->
                assertFalse(session.isAvailable, "the desktop mirrors nothing")
            }
        }
    }
}
