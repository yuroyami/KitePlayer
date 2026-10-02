package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.isAvailable
import kotlin.test.Test
import kotlin.test.assertFalse

/** The desktop has no system media session, but shared code still gets one call per platform. */
class AttachMediaSessionJvmTest {

    @Test
    fun theDesktopSessionIsTheHonestEmptyOne() {
        if (!KitePlayer.isAvailable) return println("SKIP: no desktop player")
        KitePlayer().use { player ->
            player.attachMediaSession().use { session ->
                assertFalse(session.isAvailable, "the desktop mirrors nothing")
            }
        }
    }
}
