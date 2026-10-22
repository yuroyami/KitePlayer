package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import io.github.yuroyami.kiteplayer.internal.chooseProgram
import io.github.yuroyami.kiteplayer.internal.programCandidates
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * One channel of a multiplex plays with its own tracks (#505). The scripted multiplex has two
 * channels that share the picture and differ in everything else: ChannelA has French sound and
 * Spanish subtitles, and ChannelB has English sound and Spanish subtitles of its own. English
 * sound is preferred, so a player that ranked every stream of the file would play ChannelB's sound
 * on ChannelA.
 */
class ProgramSelectionTest {

    private fun cue(text: String): SubtitleCue = SubtitleCue.Text(500_000, 20_000_000, listOf(StyledSpan(text)))

    private val channels = listOf(
        MediaProgram(101, listOf(TrackId(0), TrackId(2), TrackId(3)), name = "ChannelA", provider = "Kite"),
        MediaProgram(202, listOf(TrackId(0), TrackId(1), TrackId(4)), name = "ChannelB"),
    )

    /** Video 0, English sound 1, French sound 2, and Spanish subtitles 3 and 4. */
    private fun multiplex(
        programs: List<MediaProgram> = channels,
        live: Boolean = false,
        seekable: Boolean = true,
    ) = MediaScript(
        durationUs = 30_000_000,
        additionalAudioTracks = listOf(ScriptedAudioTrack(index = 2, marker = 2f, language = "fre", title = "ChannelA sound")),
        additionalSubtitleTracks = listOf(
            ScriptedSubtitleTrack(index = 3, cues = listOf(cue("A")), language = "spa", title = "ChannelA subtitles"),
            ScriptedSubtitleTrack(index = 4, cues = listOf(cue("B")), language = "spa", title = "ChannelB subtitles"),
        ),
        programs = programs,
        live = live,
        seekable = seekable,
    )

    private val preferences = PlayerConfig(
        audio = AudioConfig(preferredLanguages = listOf("eng")),
        subtitles = SubtitleConfig(preferredLanguages = listOf("spa")),
    )

    private suspend fun TestScope.opened(script: MediaScript = multiplex(), program: Int? = null): CoreHarness {
        val harness = CoreHarness(this, script = script, config = preferences)
        harness.attachRenderer()
        harness.core.open(MediaItem("scripted://multiplex", demux = DemuxPolicy(program = program)))
        return harness
    }

    private val CoreHarness.tracks: Tracks get() = core.snapshots.value.tracks

    @Test
    fun theOpenPlaysTheFirstChannelWithAPictureAndOnlyItsTracks() = runTest {
        val harness = opened()
        assertEquals(channels, harness.tracks.programs, "the track list names the channels")
        assertEquals(101, harness.tracks.selectedProgram)
        assertEquals(TrackId(0), harness.tracks.selectedVideo)
        assertEquals(TrackId(2), harness.tracks.selectedAudio, "ChannelA's French sound, not ChannelB's English")
        assertEquals(TrackId(3), harness.tracks.selectedSubtitle, "ChannelA's own subtitles")
        assertEquals(listOf(101, 202), harness.tracks.programsOf(TrackId(0)).map { it.number })
        assertEquals(listOf(202), harness.tracks.programsOf(TrackId(1)).map { it.number })
        harness.close()
    }

    @Test
    fun anItemThatNamesAChannelPlaysThatChannelsTracks() = runTest {
        val harness = opened(program = 202)
        assertEquals(202, harness.tracks.selectedProgram)
        assertEquals(TrackId(1), harness.tracks.selectedAudio)
        assertEquals(TrackId(4), harness.tracks.selectedSubtitle, "ChannelB's subtitles, though ChannelA's match as well")
        harness.close()
    }

    @Test
    fun aChannelTheMediaDoesNotHaveChoosesAsIfNoneWasNamed() = runTest {
        val harness = opened(program = 999)
        assertEquals(101, harness.tracks.selectedProgram)
        assertEquals(TrackId(2), harness.tracks.selectedAudio)
        harness.close()
    }

    @Test
    fun mediaWithOneProgrammeOrNoneChoosesFromEveryTrack() = runTest {
        val plain = opened(multiplex(programs = emptyList()))
        assertTrue(plain.tracks.programs.isEmpty())
        assertEquals(null, plain.tracks.selectedProgram)
        assertEquals(TrackId(1), plain.tracks.selectedAudio, "the preferred English sound, as before")
        plain.close()

        // A transport stream with one channel can list a stream FFmpeg found outside its table.
        val single = opened(multiplex(programs = channels.take(1)))
        assertEquals(101, single.tracks.selectedProgram)
        assertEquals(TrackId(1), single.tracks.selectedAudio, "one channel restricts nothing")
        single.close()
    }

    @Test
    fun aKindTheChannelLacksComesFromTheTracksInNoChannel() = runTest {
        // ChannelA holds no sound, and English sits in no channel while French sits in ChannelB.
        val programs = listOf(
            MediaProgram(101, listOf(TrackId(0), TrackId(3))),
            MediaProgram(202, listOf(TrackId(0), TrackId(2), TrackId(4))),
        )
        val harness = opened(multiplex(programs = programs), program = 101)
        assertEquals(TrackId(1), harness.tracks.selectedAudio)
        harness.close()
    }

    @Test
    fun switchingChannelWhilePlayingChangesEveryTrackAtThePosition() = runTest {
        val harness = opened()
        harness.core.play()
        harness.run(3.seconds)
        val before = harness.core.position()
        val opensBefore = harness.backend.openCalls

        harness.core.selectProgram(202)
        harness.run(500.milliseconds)

        assertEquals(opensBefore + 1, harness.backend.openCalls, "the item opened again")
        assertEquals(202, harness.backend.lastOpenedItem?.demux?.program, "on the chosen channel")
        assertEquals(202, harness.core.snapshots.value.media?.demux?.program, "kept on the item")
        assertEquals(202, harness.tracks.selectedProgram)
        assertEquals(TrackId(1), harness.tracks.selectedAudio)
        assertEquals(TrackId(4), harness.tracks.selectedSubtitle)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        val after = harness.core.position()
        assertTrue(abs((after - before).inWholeMilliseconds) < 1_500, "it went on near $before, not from $after")

        // Asking for the channel that plays changes nothing.
        harness.core.selectProgram(202)
        assertEquals(opensBefore + 1, harness.backend.openCalls)
        // Null chooses again, and the first channel with a picture is ChannelA.
        harness.core.selectProgram(null)
        harness.run(500.milliseconds)
        assertEquals(101, harness.tracks.selectedProgram)
        assertEquals(TrackId(2), harness.tracks.selectedAudio)
        harness.close()
    }

    @Test
    fun aTrackAskedForBesideTheChannelChangeIsKept() = runTest {
        val harness = opened()
        harness.core.selectTrack(TrackKind.Audio, TrackId(1))
        harness.run(200.milliseconds)
        // Sent together, so both wait for the one rebuild, and the sound asked for wins over the
        // one the channel would have chosen.
        val program = async { harness.core.selectProgram(202) }
        val audio = async { harness.core.selectTrack(TrackKind.Audio, TrackId(2)) }
        harness.run(500.milliseconds)
        program.await()
        assertEquals(TrackChange.Applied(TrackKind.Audio, TrackId(2)), audio.await())
        assertEquals(202, harness.tracks.selectedProgram)
        assertEquals(TrackId(2), harness.tracks.selectedAudio)
        assertEquals(TrackId(4), harness.tracks.selectedSubtitle, "the subtitles still come from the channel")
        harness.close()
    }

    @Test
    fun aLiveSenderJoinsTheNewChannelWhereItIsWithoutASeek() = runTest {
        val harness = opened(multiplex(live = true, seekable = false))
        harness.core.play()
        harness.run(3.seconds)

        harness.core.selectProgram(202)
        harness.run(1.seconds)

        assertEquals(202, harness.tracks.selectedProgram)
        assertEquals(TrackId(1), harness.tracks.selectedAudio)
        assertTrue(harness.source.seekTargets.isEmpty(), "a live sender is not sought: ${harness.source.seekTargets}")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
    }

    @Test
    fun aChangeThatCannotApplyIsRefused() = runTest {
        val harness = opened()
        assertFailsWith<IllegalArgumentException> { harness.core.selectProgram(7) }
        harness.close()

        val plain = opened(multiplex(programs = emptyList()))
        assertFailsWith<IllegalArgumentException> { plain.core.selectProgram(101) }
        plain.close()

        // A source that can neither seek nor be joined live, as a pipe cannot.
        val pipe = opened(multiplex(seekable = false))
        assertFailsWith<UnsupportedOperationException> { pipe.core.selectProgram(202) }
        pipe.close()
    }

    @Test
    fun theRulesPickTheChannelAndItsCandidates() {
        fun stream(index: Int, kind: TrackKind, coverArt: Boolean = false) =
            io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo(index = index, kind = kind, codec = "x", isCoverArt = coverArt)
        val streams = listOf(
            stream(0, TrackKind.Video, coverArt = true),
            stream(1, TrackKind.Audio),
            stream(2, TrackKind.Video),
            stream(3, TrackKind.Audio),
            stream(4, TrackKind.Subtitle),
        )
        val art = MediaProgram(1, listOf(TrackId(0), TrackId(1)))
        val film = MediaProgram(2, listOf(TrackId(2), TrackId(3)))
        val empty = MediaProgram(3, emptyList())
        val programs = listOf(empty, art, film)

        assertEquals(film, chooseProgram(programs, streams, asked = null), "a picture that is not cover art first")
        assertEquals(art, chooseProgram(programs, streams, asked = 1))
        assertEquals(art, chooseProgram(listOf(empty, art), streams.take(2), asked = null), "then any channel with a track")
        assertEquals(null, chooseProgram(listOf(empty), streams, asked = null))
        assertEquals(null, chooseProgram(emptyList(), streams, asked = 5))

        assertEquals(listOf(2, 3, 4), programCandidates(streams, programs, film).map { it.index }, "the subtitle sits in no channel")
        assertEquals(streams, programCandidates(streams, listOf(film), film), "one channel restricts nothing")
        assertEquals(streams, programCandidates(streams, programs, null))
    }
}
