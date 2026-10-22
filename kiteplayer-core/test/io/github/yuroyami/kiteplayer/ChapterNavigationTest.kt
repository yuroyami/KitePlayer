@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Next and previous chapter on the facade. Previous follows the rule every music player has had
 * for decades: well into a chapter it restarts the chapter, and within its first three seconds it
 * goes back one, because a second press is how a listener asks for the one before.
 */
class ChapterNavigationTest {

    private fun chaptered(scope: TestScope): Pair<CoreHarness, KitePlayer> {
        val harness = CoreHarness(
            scope,
            script = MediaScript(
                durationUs = 30_000_000,
                chapters = listOf(
                    Chapter(index = 0, start = 0.seconds, end = 10.seconds, title = "one"),
                    Chapter(index = 1, start = 10.seconds, end = 20.seconds, title = "two"),
                    Chapter(index = 2, start = 20.seconds, end = null, title = "three"),
                ),
            ),
        )
        return harness to KitePlayer(harness.core)
    }

    @Test
    fun `previousChapter well into a chapter restarts it`() = runTest {
        val (harness, player) = chaptered(this)
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        player.seek(15.seconds)
        harness.run(100.milliseconds)

        player.previousChapter()
        harness.run(100.milliseconds)

        assertEquals(10.seconds, player.position(), "five seconds into chapter two goes to its start")
        harness.close()
    }

    @Test
    fun `previousChapter within three seconds of the start goes back one chapter`() = runTest {
        val (harness, player) = chaptered(this)
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        player.seek(11.seconds)
        harness.run(100.milliseconds)

        player.previousChapter()
        harness.run(100.milliseconds)

        assertEquals(0.seconds, player.position(), "one second into chapter two goes to chapter one")
        harness.close()
    }

    @Test
    fun `previousChapter at the first chapter restarts it`() = runTest {
        val (harness, player) = chaptered(this)
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        player.seek(1.seconds)
        harness.run(100.milliseconds)

        player.previousChapter()
        harness.run(100.milliseconds)

        assertEquals(0.seconds, player.position(), "there is nothing before the first chapter but its start")
        harness.close()
    }

    @Test
    fun `nextChapter goes to the start of the following chapter`() = runTest {
        val (harness, player) = chaptered(this)
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        player.seek(15.seconds)
        harness.run(100.milliseconds)

        player.nextChapter()
        harness.run(100.milliseconds)

        assertEquals(20.seconds, player.position())
        harness.close()
    }

    @Test
    fun `nextChapter at the last chapter does nothing`() = runTest {
        val (harness, player) = chaptered(this)
        harness.openWithRenderer()
        harness.run(100.milliseconds)
        player.seek(25.seconds)
        harness.run(100.milliseconds)

        player.nextChapter()
        harness.run(100.milliseconds)

        assertEquals(25.seconds, player.position(), "no chapter follows, so nothing moves and nothing throws")
        harness.close()
    }
}
