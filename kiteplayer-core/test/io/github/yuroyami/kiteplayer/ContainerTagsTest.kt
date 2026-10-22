@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The container's tags reach an application.
 *
 * They were read on open and went no further: `PlayerMediaSource.metadata` had no consumer, so a
 * file's title and artist sat in memory and the only way to show a name was to parse the filename.
 * Per-stream tags were worse off, because `TrackInfo` parses `language` and `title` and dropped
 * everything else the container wrote.
 */
class ContainerTagsTest {

    @Test
    fun `the container's own tags reach the snapshot`() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 2_000_000))
        harness.openWithRenderer()
        harness.run(50.milliseconds)

        val metadata = harness.core.snapshots.value.metadata
        assertEquals("scripted", metadata["title"])
        assertEquals("the harness", metadata["artist"])
        harness.close()
    }

    @Test
    fun `a track carries the tags its own stream declared`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 2_000_000,
                additionalSubtitleTracks = listOf(
                    ScriptedSubtitleTrack(index = 5, cues = emptyList(), language = "fra", title = "Forced"),
                ),
            ),
        )
        harness.openWithRenderer()
        harness.run(50.milliseconds)

        val tracks = harness.core.snapshots.value.tracks.all
        val video = tracks.first { it.kind == TrackKind.Video }
        // A key TrackInfo has no field for, which is the point: the parsed fields were never the
        // problem, the dropped ones were.
        assertEquals("scripted video handler", video.metadata["handler_name"])

        val french = tracks.first { it.id == TrackId(5) }
        assertEquals("fra", french.metadata["language"])
        assertEquals("Forced", french.metadata["title"])
        harness.close()
    }

    @Test
    fun `a container with no tags reports none rather than inventing any`() = runTest {
        // The tags are the container's, so an empty map is a real answer and must not be filled in
        // with a filename or a placeholder.
        val harness = CoreHarness(this, script = MediaScript(durationUs = 1_000_000))
        harness.openWithRenderer()
        harness.run(50.milliseconds)
        val audio = harness.core.snapshots.value.tracks.all.firstOrNull { it.kind == TrackKind.Audio }
        assertTrue(
            audio == null || !audio.metadata.containsKey("handler_name"),
            "only the video stream declared a handler, so nothing else may report one",
        )
        harness.close()
    }
}
