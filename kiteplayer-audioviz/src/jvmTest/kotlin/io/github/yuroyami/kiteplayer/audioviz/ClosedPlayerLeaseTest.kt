package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull

/** The visualiser can leave the screen after the app closed its player (#227). */
class ClosedPlayerLeaseTest {

    @Test
    fun `releasing the last lease of a closed player does not throw`() = runBlocking {
        val player = assertNotNull(KitePlayerPlatform.createOrNull(), "no default desktop player")
        val lease = playerAudioVizSessions.acquire(player)
        player.closeAndAwait()
        // What onDispose does in rememberAudioVizState.
        lease.close()
    }
}
