package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A lock-screen or headset press can arrive after the player closed, and must not throw (#201). */
class RemotePressAfterCloseTest {

    @Test
    fun `remote play and pause on a closed player do nothing`() = runBlocking {
        val player = KitePlayerPlatform.createOrNull() ?: return@runBlocking println("SKIP: no desktop player")
        player.closeAndAwait()
        // The player itself refuses, which is what the session targets have to absorb.
        assertFailsWith<IllegalStateException> { player.play() }

        player.playFromRemote()
        player.pauseFromRemote()
        val target = PlayerSessionTarget(player)
        target.play()
        target.pause()
    }
}
