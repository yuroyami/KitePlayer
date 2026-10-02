package io.github.yuroyami.kiteplayer.session

import io.github.yuroyami.kiteplayer.Backends
import io.github.yuroyami.kiteplayer.KitePlayer
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.MonotonicClock
import io.github.yuroyami.kiteplayer.PlayerConfig
import io.github.yuroyami.kiteplayer.spi.AudioSink
import io.github.yuroyami.kiteplayer.spi.AudioSinkFactory
import io.github.yuroyami.kiteplayer.spi.BackendSession
import io.github.yuroyami.kiteplayer.spi.MediaBackend
import io.github.yuroyami.kiteplayer.spi.OutputBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/** The parts a media session owns close in reverse, once, and with the player. */
class SessionPartsTest {

    private class Recorded(private val name: String, private val log: MutableList<String>) : AutoCloseable {
        override fun close() {
            log += name
        }
    }

    @Test
    fun partsCloseNewestFirstAndOnce() {
        val log = mutableListOf<String>()
        val parts = SessionParts()
        parts.add(Recorded("interruptions", log))
        parts.add(Recorded("background", log))
        parts.add(Recorded("notification", log))
        parts.closeAll()
        parts.closeAll()
        assertEquals(listOf("notification", "background", "interruptions"), log)
    }

    @Test
    fun aPartAddedAfterTheCloseClosesAtOnce() {
        val log = mutableListOf<String>()
        val parts = SessionParts()
        parts.closeAll()
        parts.add(Recorded("late", log))
        assertEquals(listOf("late"), log)
    }

    @Test
    fun aFailingPartDoesNotStopTheRest() {
        val log = mutableListOf<String>()
        val parts = SessionParts()
        parts.add(Recorded("first", log))
        val failure = IllegalStateException("broken part")
        parts.add(AutoCloseable { throw failure })
        parts.add(Recorded("last", log))
        val thrown = assertFailsWith<IllegalStateException> { parts.closeAll() }
        assertSame(failure, thrown)
        assertEquals(listOf("last", "first"), log)
    }

    @Test
    fun theWaitEndsWithThePlayer() = runBlocking {
        val player = KitePlayer.create(PlayerConfig(backends = Backends(backend = StubMediaBackend, output = StubOutputBackend)))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val closed = CompletableDeferred<Unit>()
        try {
            scope.closeWithPlayer(player) { closed.complete(Unit) }
            player.close()
            withTimeout(5_000) { closed.await() }
        } finally {
            scope.cancel()
        }
    }
}

/** Never opened: these tests only build and close players. */
private object StubMediaBackend : MediaBackend {
    override suspend fun open(media: MediaItem): BackendSession = error("no media in this test")
}

private object StubOutputBackend : OutputBackend {
    override val clock: MonotonicClock = MonotonicClock.System
    override val audioSink: AudioSinkFactory = object : AudioSinkFactory {
        override val name: String = "stub"
        override suspend fun create(): AudioSink = error("no audio in this test")
    }
}
