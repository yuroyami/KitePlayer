@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Tags that change while a stream plays (#423): a station's next song shows when it is heard, not
 * when its packet is read seconds ahead, and an application's own details and the station's song
 * replace each other, whichever came last.
 */
class StreamTagsTest {

    private val radio = MediaScript(
        durationUs = 10_000_000,
        tagChanges = listOf(3_000_000L to mapOf("StreamTitle" to "First Song"), 6_000_000L to mapOf("StreamTitle" to "Second Song")),
    )

    private fun CoreHarness.song(): String? = core.snapshots.value.metadata["StreamTitle"]

    @Test
    fun aSongShowsWhenItIsHeard() = runTest {
        val harness = CoreHarness(this, script = radio)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(2_000.milliseconds)
        // The demux has read past three seconds by now, and the song is not heard yet.
        assertTrue(harness.source.demuxFrontierUs > 3_000_000, "the demux read only to ${harness.source.demuxFrontierUs}")
        assertNull(harness.song(), "the song showed when its packet was read, at ${harness.core.position()}")
        harness.run(1_300.milliseconds)
        assertEquals("First Song", harness.song(), "at ${harness.core.position()}")
        assertEquals("the harness", harness.core.snapshots.value.metadata["artist"], "the open's other tags were lost")
        harness.run(3_000.milliseconds)
        assertEquals("Second Song", harness.song())
        harness.close()
    }

    @Test
    fun itemDetailsAndTheStationsSongReplaceEachOther() = runTest {
        val harness = CoreHarness(this, script = radio)
        harness.openWithRenderer()
        harness.core.play()
        harness.run(3_300.milliseconds)
        assertEquals("First Song", harness.song())
        harness.core.setItemDetails("From the app", "Some Artist", null)
        val media = harness.core.snapshots.value.media
        assertEquals(listOf("From the app", "Some Artist", null), listOf(media?.title, media?.artist, media?.album))
        assertNull(harness.song(), "the station's song stayed over the application's details")
        harness.run(3_000.milliseconds)
        assertEquals("Second Song", harness.song(), "the station's next song did not show")
        assertEquals("From the app", harness.core.snapshots.value.media?.title)
        harness.close()
    }

    @Test
    fun detailsNeedAnOpenItem() = runTest {
        val harness = CoreHarness(this, script = radio)
        val refused = runCatching { harness.core.setItemDetails("x", null, null) }.exceptionOrNull()
        assertTrue(refused is IllegalStateException, "nothing open, and the details were taken: $refused")
        assertFalse(harness.core.snapshots.value.metadata.containsKey("StreamTitle"))
        harness.close()
    }
}
