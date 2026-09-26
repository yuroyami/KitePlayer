package io.github.yuroyami.kiteplayer.audioviz

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AudioVizSessionsTest {
    @Test
    fun `views share one worker until the last lease closes`() {
        val dispatcher = ManualVizDispatcher()
        var attached = 0
        var detached = 0
        var created = 0
        val sessions = AudioVizSessions<Any>(
            attach = { _, _ -> attached++ },
            detach = { _, _ -> detached++ },
            create = { created++; AudioVizFeed(dispatcher) },
        )
        val player = Any()
        val first = sessions.acquire(player)
        val second = sessions.acquire(player)
        assertSame(first.feed, second.feed)
        assertEquals(1, attached)
        assertEquals(1, created)
        first.close()
        first.close()
        assertFalse(second.feed.isClosed)
        assertEquals(0, detached)
        second.close()
        dispatcher.runAll()
        assertTrue(second.feed.isClosed)
        assertEquals(1, detached)
        assertEquals(0L, second.feed.stats.queuedPcmNanos)

        val later = sessions.acquire(player)
        assertNotSame(second.feed, later.feed)
        assertEquals(2, created)
        later.close()
        dispatcher.runAll()
        assertEquals(2, detached)
    }

    // Attaching starts the song scanner, so the view's settings must already be in place (#289).
    @Test
    fun `a view configures the feed before it attaches and again when it shares one`() {
        val dispatcher = ManualVizDispatcher()
        val order = ArrayList<String>()
        val sessions = AudioVizSessions<Any>(
            attach = { _, _ -> order += "attach" },
            detach = { _, _ -> },
            create = { AudioVizFeed(dispatcher) },
        )
        val player = Any()
        val first = sessions.acquire(player) { order += "configure first" }
        val second = sessions.acquire(player) { order += "configure second" }
        assertEquals(listOf("configure first", "attach", "configure second"), order)
        first.close()
        second.close()
        dispatcher.runAll()
    }

    @Test
    fun `different players keep independent analysis lifetimes`() {
        val dispatcher = ManualVizDispatcher()
        val sessions = AudioVizSessions<Any>({ _, _ -> }, { _, _ -> }, { AudioVizFeed(dispatcher) })
        val first = sessions.acquire(Any())
        val second = sessions.acquire(Any())
        assertNotSame(first.feed, second.feed)
        first.close()
        dispatcher.runAll()
        assertTrue(first.feed.isClosed)
        assertFalse(second.feed.isClosed)
        second.close()
        dispatcher.runAll()
    }

    @Test
    fun `an attachment failure releases its worker and permits another acquisition`() {
        val dispatcher = ManualVizDispatcher()
        val created = mutableListOf<AudioVizFeed>()
        var fail = true
        val sessions = AudioVizSessions<Any>(
            attach = { _, _ -> if (fail) error("attachment failed") },
            detach = { _, _ -> },
            create = { AudioVizFeed(dispatcher).also { created += it } },
        )
        val player = Any()
        assertFailsWith<IllegalStateException> { sessions.acquire(player) }
        dispatcher.runAll()
        assertTrue(created.single().isClosed)
        fail = false
        val lease = sessions.acquire(player)
        assertNotSame(created.first(), lease.feed)
        lease.close()
        dispatcher.runAll()
        assertTrue(lease.feed.isClosed)
    }
}
