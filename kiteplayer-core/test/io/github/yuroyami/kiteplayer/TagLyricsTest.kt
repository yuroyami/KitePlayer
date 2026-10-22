@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The lyrics a song's own tags carry (#443): stamped as LRC they are a track that shows line by line,
 * and without stamps they are text for the application to show.
 */
class TagLyricsTest {

    private val synced = "[ar:Somebody]\n[00:00.50]First line\n[00:01.50]Second line"

    private fun texts(harness: CoreHarness) =
        harness.core.subtitleCues.value.filterIsInstance<SubtitleCue.Text>().map { it.plainText }

    private fun lyricsTrack(harness: CoreHarness): TrackInfo? =
        harness.core.snapshots.value.tracks.all.firstOrNull { it.codec == "tag/lrc" }

    @Test
    fun stampedLyricsInAnId3FrameShowLineByLine() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000, containerTags = mapOf("lyrics-eng" to synced)))
        harness.openWithRenderer()
        val track = lyricsTrack(harness)
        assertTrue(track != null, "no lyrics track: ${harness.core.snapshots.value.tracks.all}")
        assertEquals("eng", track.language)
        assertEquals(track.id, harness.core.snapshots.value.tracks.selectedSubtitle, "a song's lyrics were not selected")
        assertNull(harness.core.snapshots.value.lyrics, "stamped lyrics were repeated as text")
        harness.core.play()
        harness.run(800.milliseconds)
        assertEquals(listOf("First line"), texts(harness))
        harness.run(1_000.milliseconds)
        assertEquals(listOf("Second line"), texts(harness))
        harness.close()
    }

    @Test
    fun plainLyricsInTheAudioStreamAreTextAndNoTrack() = runTest {
        val words = "A first line\nA second line"
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000, audioMetadata = mapOf("LYRICS" to words)))
        harness.openWithRenderer()
        assertEquals(words, harness.core.snapshots.value.lyrics)
        assertNull(lyricsTrack(harness), "plain lyrics became a track")
        harness.close()
    }

    @Test
    fun aSongWithoutLyricsHasNone() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000))
        harness.openWithRenderer()
        assertNull(harness.core.snapshots.value.lyrics)
        assertNull(lyricsTrack(harness))
        harness.close()
    }

    @Test
    fun theContainersOwnSubtitleIsLeftSelected() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                durationUs = 3_000_000,
                containerTags = mapOf("lyrics" to synced),
                subtitleCues = listOf(SubtitleCue.Text(500_000, 2_000_000, listOf(StyledSpan("from the container")))),
            ),
        )
        harness.openWithRenderer()
        val track = lyricsTrack(harness)
        assertTrue(track != null, "the lyrics track was not offered beside the container's")
        assertTrue(harness.core.snapshots.value.tracks.selectedSubtitle != track.id, "the lyrics displaced the container's subtitle")
        assertTrue(harness.core.selectTrack(TrackKind.Subtitle, track.id) is TrackChange.Applied)
        harness.core.play()
        harness.run(800.milliseconds)
        assertEquals(listOf("First line"), texts(harness))
        harness.close()
    }

    @Test
    fun lyricsFromTagsCannotBeReadAgain() = runTest {
        val harness = CoreHarness(this, script = MediaScript(durationUs = 3_000_000, containerTags = mapOf("lyrics-eng" to synced)))
        harness.openWithRenderer()
        val track = assertNotNullTrack(lyricsTrack(harness))
        assertFailsWith<IllegalArgumentException> { harness.core.reloadExternalSubtitle(track.id, "UTF-8") }
        harness.close()
    }

    @Test
    fun noSubtitleIsSelectedWhenTheConfigurationSelectsNone() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 3_000_000, containerTags = mapOf("lyrics" to synced)),
            config = PlayerConfig(subtitles = SubtitleConfig(autoSelect = false)),
        )
        harness.openWithRenderer()
        assertTrue(lyricsTrack(harness) != null)
        assertNull(harness.core.snapshots.value.tracks.selectedSubtitle)
        harness.close()
    }

    private fun assertNotNullTrack(track: TrackInfo?): TrackInfo {
        assertTrue(track != null, "no lyrics track")
        return track
    }
}
