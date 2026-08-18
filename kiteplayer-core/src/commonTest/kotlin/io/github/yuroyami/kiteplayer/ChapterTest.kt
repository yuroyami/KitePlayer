@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Chapters on the facade (S4.e): the snapshot carries the container's table, boundary crossings
 * emit ChapterChanged for playback and for seeks alike, and seekToChapter lands on the start.
 */
class ChapterTest {

    private fun chapteredScript() = MediaScript(
        durationUs = 4_000_000,
        chapters = listOf(
            Chapter(index = 0, start = 0.milliseconds, end = 1_500.milliseconds, title = "one"),
            Chapter(index = 1, start = 1_500.milliseconds, end = 3_000.milliseconds, title = "two"),
            Chapter(index = 2, start = 3_000.milliseconds, end = null, title = "three"),
        ),
    )

    /**
     * KP-P1-11. A chapter table may leave gaps, and a position inside one belongs to no chapter.
     * Both readings used to ignore `end` and answer with the chapter that had already finished, so
     * a player sitting in a gap displayed the previous chapter's title as the one playing.
     */
    private fun gappedTable() = listOf(
        Chapter(index = 0, start = 0.milliseconds, end = 1_000.milliseconds, title = "one"),
        // The gap: nothing covers 1.0 s to 2.0 s.
        Chapter(index = 1, start = 2_000.milliseconds, end = 3_000.milliseconds, title = "two"),
    )

    @Test
    fun `the shared lookup answers null for a position in a gap`() {
        val table = gappedTable()
        assertEquals("one", table.chapterHolding(500_000)?.title)
        assertEquals(null, table.chapterHolding(1_500_000)?.title, "1.5 s falls in the gap")
        assertEquals("two", table.chapterHolding(2_500_000)?.title)
        assertEquals(
            null,
            table.chapterHolding(3_500_000)?.title,
            "past a declared end is a gap too, even for the last chapter",
        )
        assertEquals("one", table.chapterHolding(0)?.title, "a chapter holds its own start")
        assertEquals(
            "two",
            table.chapterHolding(2_000_000)?.title,
            "a boundary belongs to the chapter that starts on it, never to the one that ended",
        )
    }

    /**
     * KP-P1-11 through the engine. A table with a real gap must announce leaving a chapter, not
     * hold the expired one until the next begins.
     */
    @Test
    fun `playing through a gap announces that no chapter is current`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 4_000_000, chapters = gappedTable()))
        harness.openWithRenderer()
        harness.core.play()
        harness.run(5.seconds)

        val announced = harness.events.filterIsInstance<PlayerEvent.ChapterChanged>()
            .map { it.chapter?.title }
        assertEquals(
            listOf("one", null, "two", null),
            announced,
            "the gaps are chapters too, announced as none: $announced",
        )
        harness.close()
    }

    @Test
    fun `playback crosses boundaries and announces each chapter once`() = runTest {
        val harness = CoreHarness(this, script = chapteredScript())
        harness.openWithRenderer()
        harness.core.play()
        harness.run(5.seconds)

        val announced = harness.events.filterIsInstance<PlayerEvent.ChapterChanged>()
            .map { it.chapter?.title }
        assertEquals(
            listOf("one", "two", "three"),
            announced,
            "each boundary announces exactly once, in order",
        )
        harness.close()
    }

    @Test
    fun `a seek announces the chapter it lands in`() = runTest {
        val harness = CoreHarness(this, script = chapteredScript())
        harness.openWithRenderer()
        harness.run(200.milliseconds)
        harness.core.seek(Pts(2_000_000), SeekMode.Precise)
        harness.run(300.milliseconds)

        val announced = harness.events.filterIsInstance<PlayerEvent.ChapterChanged>()
            .map { it.chapter?.title }
        assertTrue("two" in announced, "the seek landed in chapter two, announced: $announced")
        harness.close()
    }

    @Test
    fun `the snapshot carries the table and unchaptered media emits nothing`() = runTest {
        val chaptered = CoreHarness(this, script = chapteredScript())
        chaptered.openWithRenderer()
        chaptered.run(200.milliseconds)
        assertEquals(3, chaptered.core.snapshots.value.chapters.size)
        chaptered.close()

        val plain = CoreHarness(this)
        plain.openWithRenderer()
        plain.core.play()
        plain.run(2.seconds)
        assertTrue(
            plain.events.filterIsInstance<PlayerEvent.ChapterChanged>().isEmpty(),
            "media with no chapter table announces nothing",
        )
        plain.close()
    }
}
