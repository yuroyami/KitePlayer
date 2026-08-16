@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The queue (S4.e): items play through in order, LoopMode.All wraps, explicit movement keeps the
 * play intent and refuses typed at the ends, and a plain open replaces the queue.
 */
class QueueTest {

    @Test
    fun `a queue of two plays through both and ends after the last`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        harness.attachRenderer()
        harness.core.openQueue(
            listOf(MediaItem("scripted://first"), MediaItem("scripted://second")),
            startIndex = 0,
        )
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        assertEquals(2, harness.core.snapshots.value.queue.size)
        harness.core.play()
        harness.run(3.seconds)

        assertEquals(
            "scripted://second",
            harness.core.snapshots.value.media?.uri,
            "the queue must have advanced to the second item",
        )
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        assertEquals(2, harness.backend.openCalls, "each item opens the backend once")

        harness.run(4.seconds)
        assertEquals(
            PlaybackStatus.Ended,
            harness.core.snapshots.value.status,
            "after the last item the queue ends instead of wrapping",
        )
        assertEquals(2, harness.backend.openCalls, "Ended at the last item opens nothing more")
        harness.close()
    }

    @Test
    fun `LoopMode All wraps the queue past its end`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 1_000_000))
        harness.attachRenderer()
        harness.core.openQueue(
            listOf(MediaItem("scripted://first"), MediaItem("scripted://second")),
            startIndex = 0,
        )
        harness.core.setLoop(LoopMode.All)
        harness.core.play()
        harness.run(5.seconds)

        assertTrue(
            harness.backend.openCalls >= 4,
            "All must wrap past the last item, saw only ${harness.backend.openCalls} opens",
        )
        assertTrue(
            harness.core.snapshots.value.status.isActive ||
                harness.core.snapshots.value.status == PlaybackStatus.Paused,
            "a wrapping queue never rests in Ended, was ${harness.core.snapshots.value.status}",
        )
        harness.close()
    }

    @Test
    fun `next and previous move the queue and refuse typed at the ends`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.attachRenderer()
        harness.core.openQueue(
            listOf(MediaItem("scripted://first"), MediaItem("scripted://second")),
            startIndex = 0,
        )
        assertFailsWith<IllegalStateException>("previous at the first item must refuse") {
            harness.core.queuePrevious()
        }
        harness.core.queueNext()
        assertEquals("scripted://second", harness.core.snapshots.value.media?.uri)
        assertEquals(1, harness.core.snapshots.value.queueIndex)
        assertFailsWith<IllegalStateException>("next at the last item must refuse") {
            harness.core.queueNext()
        }
        harness.core.queuePrevious()
        assertEquals(0, harness.core.snapshots.value.queueIndex)
        harness.close()
    }

    @Test
    fun `a plain open replaces the queue and next then refuses`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000))
        harness.attachRenderer()
        harness.core.openQueue(
            listOf(MediaItem("scripted://first"), MediaItem("scripted://second")),
            startIndex = 0,
        )
        harness.core.stop()
        harness.core.open(MediaItem("scripted://alone"))
        harness.run(100.milliseconds)
        assertEquals(0, harness.core.snapshots.value.queue.size, "a plain open replaces the queue")
        assertEquals(-1, harness.core.snapshots.value.queueIndex)
        assertFailsWith<IllegalStateException> { harness.core.queueNext() }
        harness.close()
    }
}
