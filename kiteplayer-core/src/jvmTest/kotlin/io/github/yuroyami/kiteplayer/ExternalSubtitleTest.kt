@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * External subtitle files (S4.e): a local SRT or VTT beside the media becomes a selectable
 * synthetic track, its cues time through the engine's own path, seeks keep them, and a broken
 * file warns typed instead of failing the open. JVM-hosted because the files are real files.
 */
class ExternalSubtitleTest {

    private fun srtFile(): File = File.createTempFile("kiteplayer-external", ".srt").apply {
        writeText(
            """
            1
            00:00:00,500 --> 00:00:02,000
            Hello from outside

            2
            00:00:02,500 --> 00:00:03,500
            Second cue
            """.trimIndent(),
        )
        deleteOnExit()
    }

    @Test
    fun `a local srt becomes a selectable track and its cues time and publish`() = runTest {
        val file = srtFile()
        val harness = CoreHarness(this)
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://media",
                externalSubtitles = listOf(SubtitleSource(uri = file.absolutePath, title = "Outside")),
            ),
        )
        val external = harness.core.snapshots.value.tracks.all
            .filter { it.kind == TrackKind.Subtitle && it.id.isExternal }
        assertEquals(1, external.size, "the file must appear as one synthetic track")
        assertEquals("external/subrip", external.single().codec)
        assertEquals("Outside", external.single().title)

        harness.core.selectTrack(TrackKind.Subtitle, external.single().id)
        assertEquals(
            external.single().id,
            harness.core.snapshots.value.tracks.selectedSubtitle,
            "the in-place selection must publish",
        )

        harness.core.play()
        harness.run(1.seconds)
        val overlays = harness.renderer!!.overlays
        assertTrue(
            overlays.any { it != null && it.images.isNotEmpty() || it != null },
            "a cue inside 0.5..2.0 s must publish an overlay while playing, got ${overlays.size} publications",
        )
        assertTrue(overlays.isNotEmpty(), "the cue edge must reach the renderer")
        harness.close()
    }

    @Test
    fun `seeks keep the external cue table`() = runTest {
        val file = srtFile()
        val harness = CoreHarness(this)
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://media",
                externalSubtitles = listOf(SubtitleSource(uri = file.absolutePath, selectImmediately = true)),
            ),
        )
        assertTrue(
            harness.core.snapshots.value.tracks.selectedSubtitle?.isExternal == true,
            "selectImmediately must select the synthetic track at open",
        )
        harness.core.seek(Pts(3_000_000), SeekMode.Precise)
        val publicationsAfterSeek = harness.renderer!!.overlays.size
        harness.core.play()
        harness.run(700.milliseconds)
        assertTrue(
            harness.renderer!!.overlays.size > publicationsAfterSeek,
            "the second cue (2.5..3.5 s) must still publish after the seek cleared the buffers",
        )
        harness.close()
    }

    @Test
    fun `a missing file warns typed and never fails the open`() = runTest {
        val harness = CoreHarness(this)
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://media",
                externalSubtitles = listOf(SubtitleSource(uri = "/nowhere/definitely-missing.srt")),
            ),
        )
        assertEquals(PlaybackStatus.Paused, harness.core.snapshots.value.status, "the open survives")
        assertTrue(
            harness.core.warningHistory().any {
                it.warning is PlaybackWarning.TrackDeselected && "could not be read" in it.warning.message
            },
            "the unreadable file must warn typed: ${harness.core.warningHistory().map { it.warning.message }}",
        )
        assertTrue(
            harness.core.snapshots.value.tracks.all.none { it.id.isExternal },
            "no synthetic track appears for a file that never loaded",
        )
        harness.close()
    }
    // F-EXT1: an id minted by addExternalSubtitle must never collide with a track the open
    // already created. Deriving the id from the LOADED count collided as soon as one declared
    // file had failed, because the open derives ids from the DECLARED index.
    @Test
    fun `an added subtitle never reuses the id of a track the open created`() = runTest {
        val good = srtFile()
        val added = srtFile()
        val harness = CoreHarness(this)
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://media",
                externalSubtitles = listOf(
                    SubtitleSource(uri = "/nowhere/definitely-missing.srt"),
                    SubtitleSource(uri = good.absolutePath, title = "Declared"),
                ),
            ),
        )
        val declared = harness.core.snapshots.value.tracks.all.filter { it.id.isExternal }
        assertEquals(1, declared.size, "the unreadable file is dropped, the readable one loads")

        val newId = harness.core.addExternalSubtitle(SubtitleSource(uri = added.absolutePath, title = "Added"))
        val external = harness.core.snapshots.value.tracks.all.filter { it.id.isExternal }
        assertEquals(2, external.size, "both tracks must survive as separate entries")
        assertEquals(
            external.map { it.id }.size,
            external.map { it.id }.distinct().size,
            "external track ids must be unique, got ${external.map { it.id }}",
        )
        assertTrue(newId !in declared.map { it.id }, "the added id must be fresh, got $newId")
        harness.close()
    }

    // F-EXT2: an external ASS file is labelled as what it is. The label used to be derived from
    // the vtt hint alone, so every ASS file reported itself as SubRip.
    @Test
    fun `an external ass file is labelled ass not subrip`() = runTest {
        val ass = File.createTempFile("kiteplayer-external", ".ass").apply {
            writeText(
                """
                [Script Info]
                ScriptType: v4.00+

                [Events]
                Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
                Dialogue: 0,0:00:00.50,0:00:02.00,Default,,0,0,0,,Hello
                """.trimIndent(),
            )
            deleteOnExit()
        }
        val harness = CoreHarness(this)
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://media",
                externalSubtitles = listOf(SubtitleSource(uri = ass.absolutePath)),
            ),
        )
        val external = harness.core.snapshots.value.tracks.all.filter { it.id.isExternal }
        assertEquals(1, external.size, "the ass file must load: ${harness.core.warningHistory().map { it.warning.message }}")
        assertEquals("external/ass", external.single().codec)
        harness.close()
    }
}
