@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
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

    /**
     * `selectImmediately` promises selection unconditionally, and used to be honoured
     * only when the container happened to carry no subtitle stream of its own: with one present the
     * flag was silently ignored and the viewer got the container's subtitles instead of the file
     * they asked for.
     */
    @Test
    fun `an immediate external subtitle wins over the container's own subtitle stream`() = runTest {
        val file = srtFile()
        val harness = CoreHarness(
            this,
            script = MediaScript(
                subtitleCues = listOf(
                    io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text(
                        startMicros = 100_000,
                        endMicros = 200_000,
                        spans = listOf(
                            io.github.yuroyami.kiteplayer.subtitle.StyledSpan("from the container"),
                        ),
                    ),
                ),
            ),
        )
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://media",
                externalSubtitles = listOf(
                    SubtitleSource(uri = file.absolutePath, title = "Outside", selectImmediately = true),
                ),
            ),
        )

        val selected = harness.core.snapshots.value.tracks.selectedSubtitle
        assertTrue(
            selected != null && selected.isExternal,
            "the flagged file must be the selection, not the container's stream; got $selected",
        )
        harness.close()
    }

    /**
     * The other half, and the regression the fix could have introduced: deciding to
     * skip the container's subtitle stream BEFORE knowing whether the flagged file loads would
     * leave a viewer with no subtitles at all when it does not.
     */
    @Test
    fun `an immediate external subtitle that cannot load leaves the container's stream selected`() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(
                subtitleCues = listOf(
                    io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text(
                        startMicros = 100_000,
                        endMicros = 200_000,
                        spans = listOf(
                            io.github.yuroyami.kiteplayer.subtitle.StyledSpan("from the container"),
                        ),
                    ),
                ),
            ),
        )
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://media",
                externalSubtitles = listOf(
                    SubtitleSource(
                        uri = "/nowhere/this-file-does-not-exist.srt",
                        selectImmediately = true,
                    ),
                ),
            ),
        )

        val selected = harness.core.snapshots.value.tracks.selectedSubtitle
        assertTrue(
            selected != null && !selected.isExternal,
            "with the flagged file unreadable the container's own subtitles must still play; got $selected",
        )
        harness.close()
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
    // An id minted by addExternalSubtitle must never collide with a track the open
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

    // An external ASS file is labelled as what it is. The label used to be derived from
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

    /** A container that carries its own subtitle stream, which is what makes the swap a REOPEN. */
    private fun containerScript(): MediaScript = MediaScript(
        subtitleCues = listOf(
            io.github.yuroyami.kiteplayer.subtitle.SubtitleCue.Text(
                startMicros = 100_000,
                endMicros = 200_000,
                spans = listOf(io.github.yuroyami.kiteplayer.subtitle.StyledSpan("from the container")),
            ),
        ),
    )

    /**
     * Red by completing the caller's reply in `addExternalSubtitle` at the moment the
     * file parses, instead of chaining it to the selection: the id then comes back while the
     * container's own subtitles are still the selected ones.
     */
    @Test
    fun `adding a subtitle answers only once it is really the selected one`() = runTest {
        val file = srtFile()
        val harness = CoreHarness(this, script = containerScript())
        harness.attachRenderer()
        harness.open()
        val container = harness.core.snapshots.value.tracks.selectedSubtitle
        assertTrue(
            container != null && !container.isExternal,
            "the fixture needs the container's own subtitle stream selected, got $container",
        )

        val id = harness.core.addExternalSubtitle(SubtitleSource(uri = file.absolutePath, title = "Picked"))

        assertEquals(
            id,
            harness.core.snapshots.value.tracks.selectedSubtitle,
            "the call handed back an id for a track that was not selected yet: the reopen its " +
                "selection needs had not run, and could still have failed the whole player",
        )
        harness.close()
    }

    /**
     * KP-P1-02, the other half: a selection that never applies takes its track back out.
     *
     * Displacement rather than failure, because a failure rebuilds the track table from the
     * container anyway and would pass without any rollback at all. A displaced add is the case
     * that exposes it: the appended row lives in the engine's own external table, which every
     * later rebuild copies back on top of the container's, so without the rollback a subtitle the
     * caller was told it did not get reappears in the menu for the rest of the session.
     *
     * Red by dropping the `withdrawExternalSubtitle(id)` calls from `awaitSubtitleAdd`.
     */
    @Test
    fun `a subtitle whose selection is replaced is taken back out of the track list`() = runTest {
        val file = srtFile()
        val harness = CoreHarness(this, script = containerScript())
        harness.attachRenderer()
        harness.open()
        val container = harness.core.snapshots.value.tracks.selectedSubtitle
        assertTrue(container != null && !container.isExternal, "the fixture needs a container track")
        val before = harness.core.snapshots.value.tracks.all.size

        // The add's own selection, displaced by a subtitle selection made in the same pass.
        val add = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { harness.core.addExternalSubtitle(SubtitleSource(uri = file.absolutePath)) }
        }
        val replace = async(start = CoroutineStart.UNDISPATCHED) {
            harness.core.selectTrack(TrackKind.Subtitle, container)
        }
        val refusal = add.await().exceptionOrNull()
        replace.await()
        harness.run(500.milliseconds)

        assertTrue(
            refusal is IllegalStateException,
            "an add whose selection was replaced must refuse, not hand back an id; got $refusal",
        )
        assertEquals(
            before,
            harness.core.snapshots.value.tracks.all.size,
            "a row for a track the caller was told it did not get must not stay in the menu: " +
                "${harness.core.snapshots.value.tracks.all.map { it.id }}",
        )
        harness.close()
    }
}
