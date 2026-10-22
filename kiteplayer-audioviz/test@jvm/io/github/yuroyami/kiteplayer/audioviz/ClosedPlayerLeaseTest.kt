package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.KitePlayer
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The visualiser can leave the screen after the app closed its player (#227). */
class ClosedPlayerLeaseTest {

    @Test
    fun `releasing the last lease of a closed player does not throw`() = runBlocking {
        val player = KitePlayer()
        val lease = playerAudioVizSessions.acquire(player)
        player.closeAndAwait()
        // What onDispose does in rememberAudioVizState.
        lease.close()
    }

    @Test
    fun `acquiring a lease on a closed player answers null instead of throwing`() = runBlocking {
        val player = KitePlayer()
        player.closeAndAwait()
        // The plain call refuses a closed player, which is what crashed a composition after the close.
        assertFailsWith<IllegalStateException> { playerAudioVizSessions.acquire(player) }
        assertNull(playerAudioVizSessions.acquireOrNull(player))
    }
}
