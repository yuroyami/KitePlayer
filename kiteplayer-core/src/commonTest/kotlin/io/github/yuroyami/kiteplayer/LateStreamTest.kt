package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.SeekResult
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Streams that appear after the open join the track list and play (#509). A live transport stream,
 * a UDP multicast or a tuner recording can list a sound or subtitles only once it plays, and a
 * channel can move its sound to a new stream at a programme boundary. The scripted source announces
 * each such stream on the first packet read at its time, as KiteFFmpeg does.
 */
class LateStreamTest {

    private fun lateSound(
        at: Long = 3_000_000,
        index: Int = 1,
        language: String = "fre",
    ) = ScriptedAudioTrack(index = index, marker = 2f, language = language, title = "late sound", appearsAtUs = at)

    private suspend fun TestScope.opened(script: MediaScript, config: PlayerConfig = PlayerConfig()): CoreHarness {
        val harness = CoreHarness(this, script = script, config = config)
        harness.openWithRenderer()
        return harness
    }

    private val CoreHarness.tracks: Tracks get() = core.snapshots.value.tracks

    private val CoreHarness.added: List<TrackId>
        get() = events.filterIsInstance<PlayerEvent.TracksAdded>().flatMap { event -> event.tracks.map { it.id } }

    private val CoreHarness.chosen: List<PlayerEvent.TrackChosenByPlayer>
        get() = events.filterIsInstance<PlayerEvent.TrackChosenByPlayer>()

    private val CoreHarness.heardSound: Boolean get() = sink.audibleValues.any { it != 0f }

    /**
     * A sender that pushes a picture from the start and its sound from three seconds, as a live
     * transport stream does, so nothing of the sound exists while the open reads.
     */
    private fun liveWithLateSound() =
        MediaScript(durationUs = 30_000_000, hasAudio = false, additionalAudioTracks = listOf(lateSound()), live = true)

    @Test
    fun aSoundThatAppearsAfterTheOpenPlaysWhenNoneDid() = runTest {
        val harness = opened(liveWithLateSound())
        assertEquals(null, harness.tracks.selectedAudio, "the open found no sound")
        assertTrue(harness.tracks.all.none { it.kind == TrackKind.Audio })
        assertTrue(harness.source.streams.none { it.kind == TrackKind.Audio }, "the source lists it only once it arrives")

        harness.core.play()
        harness.run(6.seconds)

        assertEquals(listOf(TrackId(1)), harness.added, "the track list gained the sound")
        assertEquals(TrackKind.Audio, harness.tracks.find(TrackId(1))?.kind)
        assertEquals(TrackId(1), harness.tracks.selectedAudio, "the player chose the sound: ${harness.core.debugState}")
        assertEquals(listOf(PlayerEvent.TrackChosenByPlayer(TrackKind.Audio, TrackId(1))), harness.chosen)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(harness.heardSound, "the sound reached the device")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aSoundTheOpenReadsAheadToPlaysFromTheStart() = runTest {
        // A recording on disk reads seconds ahead while it opens, so its late sound is listed
        // before the open ends, and playing reaches it where it starts.
        val harness = opened(MediaScript(durationUs = 12_000_000, hasAudio = false, additionalAudioTracks = listOf(lateSound())))
        assertEquals(null, harness.tracks.selectedAudio, "the open found no sound")
        harness.core.play()
        harness.run(6.seconds)

        assertEquals(listOf(TrackId(1)), harness.added)
        assertEquals(TrackId(1), harness.tracks.selectedAudio, "the player chose the sound: ${harness.core.debugState}")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(harness.heardSound, "the sound reached the device")
        val position = harness.core.position()
        assertTrue(position in 6.seconds..8.seconds, "the position kept to the clock: $position")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aViewerWhoTurnedTheSoundOffKeepsItOff() = runTest {
        val harness = opened(liveWithLateSound())
        harness.core.selectTrack(TrackKind.Audio, null)
        harness.core.play()
        harness.run(6.seconds)

        assertEquals(listOf(TrackId(1)), harness.added, "the sound is listed")
        assertEquals(null, harness.tracks.selectedAudio, "and does not play")
        assertEquals(emptyList(), harness.chosen)
        assertTrue(!harness.heardSound)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aSoundThatAppearsBesideOneThatPlaysOnlyJoinsTheList() = runTest {
        val harness = opened(MediaScript(durationUs = 12_000_000, additionalAudioTracks = listOf(lateSound(index = 2))))
        assertEquals(TrackId(1), harness.tracks.selectedAudio)

        harness.core.play()
        harness.run(6.seconds)

        assertEquals(listOf(TrackId(2)), harness.added)
        assertEquals(TrackId(1), harness.tracks.selectedAudio, "the sound that plays carries on")
        assertEquals(emptyList(), harness.chosen)

        // The new sound is cached like every other, so choosing it is an in-place switch.
        val change = harness.core.selectTrack(TrackKind.Audio, TrackId(2))
        assertEquals(TrackChange.Applied(TrackKind.Audio, TrackId(2)), change)
        assertEquals(1, harness.backend.openCalls, "without opening the item again")
        harness.run(1.seconds)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    /** A channel whose English sound moves from stream 1 to stream 2 at four seconds. */
    private fun movingSound(programmes: Boolean) = MediaScript(
        durationUs = 12_000_000,
        hasAudio = false,
        additionalAudioTracks = listOf(
            ScriptedAudioTrack(index = 1, marker = 1f, language = "eng", title = "first", isDefault = true, packetEndUs = 4_000_000),
            ScriptedAudioTrack(index = 2, marker = 2f, language = "eng", title = "second", appearsAtUs = 4_000_000),
        ),
        programs = if (programmes) listOf(MediaProgram(101, listOf(TrackId(0), TrackId(1)), name = "Channel")) else emptyList(),
        programChanges = if (programmes) {
            listOf(4_000_000L to listOf(MediaProgram(101, listOf(TrackId(0), TrackId(2)), name = "Channel")))
        } else {
            emptyList()
        },
    )

    @Test
    fun aChannelThatMovesItsSoundToANewStreamKeepsPlayingIt() = runTest {
        val harness = opened(movingSound(programmes = true))
        assertEquals(TrackId(1), harness.tracks.selectedAudio)

        harness.core.play()
        harness.run(8.seconds)

        assertEquals(listOf(TrackId(2)), harness.added)
        assertEquals(listOf(TrackId(0), TrackId(2)), harness.tracks.programs.single().tracks, "the new programme table")
        assertEquals(TrackId(2), harness.tracks.selectedAudio, "the sound followed: ${harness.core.debugState}")
        assertEquals(listOf(PlayerEvent.TrackChosenByPlayer(TrackKind.Audio, TrackId(2))), harness.chosen)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(harness.heardSound, "the new sound reached the device")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aSoundThatMovesWithNoProgrammeTableIsFollowedToo() = runTest {
        val harness = opened(movingSound(programmes = false))
        harness.core.play()
        harness.run(8.seconds)

        assertEquals(TrackId(2), harness.tracks.selectedAudio, "the sound followed: ${harness.core.debugState}")
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aSoundTheViewerChoseIsFollowedWhenTheMediaStopsCarryingIt() = runTest {
        val harness = opened(movingSound(programmes = true))
        // Chosen by the caller, so the player's own choices no longer stand for the sound, except
        // when the media stops carrying it.
        assertEquals(TrackChange.Applied(TrackKind.Audio, TrackId(1)), harness.core.selectTrack(TrackKind.Audio, TrackId(1)))
        harness.core.play()
        harness.run(8.seconds)

        assertEquals(TrackId(2), harness.tracks.selectedAudio)
        harness.close()
    }

    @Test
    fun subtitlesThatAppearAfterTheOpenAreChosenByTheOpensRules() = runTest {
        val late = SubtitleCue.Text(4_000_000, 20_000_000, listOf(StyledSpan("late")))
        val harness = opened(
            MediaScript(
                durationUs = 30_000_000,
                additionalSubtitleTracks = listOf(
                    ScriptedSubtitleTrack(index = 2, cues = listOf(late), language = "spa", appearsAtUs = 3_000_000),
                ),
                live = true,
            ),
            PlayerConfig(subtitles = SubtitleConfig(preferredLanguages = listOf("spa"))),
        )
        assertEquals(null, harness.tracks.selectedSubtitle)

        harness.core.play()
        harness.run(6.seconds)

        assertEquals(listOf(TrackId(2)), harness.added)
        assertEquals(TrackId(2), harness.tracks.selectedSubtitle, "the preferred language was chosen: ${harness.core.debugState}")
        assertEquals(listOf(PlayerEvent.TrackChosenByPlayer(TrackKind.Subtitle, TrackId(2))), harness.chosen)
        assertEquals(
            listOf("late"),
            harness.core.subtitleCues.value.filterIsInstance<SubtitleCue.Text>().map { it.plainText },
            "its cue shows",
        )
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun subtitlesTheViewerTurnedOffStayOff() = runTest {
        val late = SubtitleCue.Text(4_000_000, 20_000_000, listOf(StyledSpan("late")))
        val harness = opened(
            MediaScript(
                durationUs = 30_000_000,
                additionalSubtitleTracks = listOf(
                    ScriptedSubtitleTrack(index = 2, cues = listOf(late), language = "spa", appearsAtUs = 3_000_000),
                ),
                live = true,
            ),
            PlayerConfig(subtitles = SubtitleConfig(preferredLanguages = listOf("spa"))),
        )
        harness.core.selectTrack(TrackKind.Subtitle, null)
        harness.core.play()
        harness.run(6.seconds)

        assertEquals(listOf(TrackId(2)), harness.added)
        assertEquals(null, harness.tracks.selectedSubtitle)
        assertEquals(emptyList(), harness.chosen)
        harness.close()
    }

    /**
     * A sender whose sound plays from the start and whose picture starts a little after three
     * seconds, between two keyframes, as a radio service that adds a slideshow does (#527).
     */
    private fun liveWithLatePicture() = MediaScript(durationUs = 30_000_000, videoAppearsAtUs = 3_010_000, live = true)

    /** A channel whose picture moves from stream 0 to stream 5 at four seconds, as at a programme boundary. */
    private fun movingPicture(live: Boolean = false) = MediaScript(
        durationUs = if (live) 30_000_000 else 12_000_000,
        videoEndUs = 4_000_000,
        movedVideoIndex = 5,
        live = live,
    )

    private val CoreHarness.shownUs: List<Long> get() = checkNotNull(renderer).presentations.map { it.pts.micros }

    @Test
    fun aPictureThatAppearsAfterTheOpenPlaysWhenNoneDid() = runTest {
        val harness = opened(liveWithLatePicture())
        assertEquals(null, harness.tracks.selectedVideo, "the open found no picture")
        assertTrue(harness.source.streams.none { it.kind == TrackKind.Video }, "the source lists it only once it arrives")

        harness.core.play()
        harness.run(6.seconds)

        assertEquals(listOf(TrackId(0)), harness.added, "the track list gained the picture")
        assertEquals(TrackId(0), harness.tracks.selectedVideo, "the player chose the picture: ${harness.core.debugState}")
        assertEquals(listOf(PlayerEvent.TrackChosenByPlayer(TrackKind.Video, TrackId(0))), harness.chosen)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        val shown = harness.shownUs
        assertEquals(3_200_000, shown.firstOrNull(), "the picture starts at its first keyframe: ${shown.take(3)}")
        assertTrue(shown.last() > 5_000_000, "and plays on: ${shown.takeLast(3)}")
        assertEquals(1, harness.backend.openCalls, "without opening the item again")
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(harness.heardSound, "the sound plays on")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aChannelThatMovesItsPictureToANewStreamKeepsShowingIt() = runTest {
        val harness = opened(movingPicture())
        assertEquals(TrackId(0), harness.tracks.selectedVideo)

        harness.core.play()
        harness.run(8.seconds)

        assertEquals(listOf(TrackId(5)), harness.added)
        assertEquals(TrackId(5), harness.tracks.selectedVideo, "the picture followed: ${harness.core.debugState}")
        assertEquals(listOf(PlayerEvent.TrackChosenByPlayer(TrackKind.Video, TrackId(5))), harness.chosen)
        assertEquals(PlaybackStatus.Playing, harness.core.snapshots.value.status)
        val shown = harness.shownUs
        assertEquals(3_960_000, shown.last { it < 4_000_000 }, "the old picture showed to its end")
        val moved = shown.filter { it >= 4_000_000 }
        assertTrue(moved.isNotEmpty() && moved.first() <= 4_200_000, "the new picture took over at the move: ${moved.take(3)}")
        assertTrue(moved.last() > 7_000_000, "and plays on: ${moved.takeLast(3)}")
        assertEquals(1, harness.backend.openCalls, "without opening the item again")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aLiveChannelsMovedPictureIsFollowedAndTheEndedOneIsNotChosenBack() = runTest {
        val harness = opened(movingPicture(live = true))
        harness.core.play()
        harness.run(8.seconds)

        assertEquals(TrackId(5), harness.tracks.selectedVideo, "the picture followed: ${harness.core.debugState}")
        assertTrue(harness.shownUs.last() > 6_000_000, "the new picture plays: ${harness.shownUs.takeLast(3)}")
        // The source cannot seek, and choosing a picture read into a cache needs no seek, so the
        // choice is answered on its merits: the old picture carries nothing at the position.
        val change = harness.core.selectTrack(TrackKind.Video, TrackId(0))
        assertTrue(change is TrackChange.Discarded, "the ended picture was not chosen: $change")
        assertEquals(TrackId(5), harness.tracks.selectedVideo)
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aSeekBackAcrossTheMovePlaysThePictureThatPlayedThere() = runTest {
        val harness = opened(movingPicture())
        harness.core.play()
        harness.run(6.seconds)
        assertEquals(TrackId(5), harness.tracks.selectedVideo)

        val seen = checkNotNull(harness.renderer).presentations.size
        assertIs<SeekResult.Applied>(harness.core.seek(Pts(2_000_000), SeekMode.Precise))
        harness.run(1.seconds)
        assertEquals(TrackId(0), harness.tracks.selectedVideo, "the picture that played there came back: ${harness.core.debugState}")
        val shown = harness.shownUs.drop(seen)
        assertTrue(shown.isNotEmpty() && shown.first() in 2_000_000..2_040_000, "the seek showed the old picture: ${shown.take(3)}")
        val position = harness.core.position()
        assertTrue(position in 2.5.seconds..3.5.seconds, "the position kept to the target: $position")

        harness.run(4.seconds)
        assertEquals(TrackId(5), harness.tracks.selectedVideo, "playing past the move moved it again")
        assertEquals(
            listOf(TrackId(5), TrackId(0), TrackId(5)),
            harness.chosen.filter { it.kind == TrackKind.Video }.map { it.track },
        )
        assertEquals(1, harness.backend.openCalls, "without opening the item again")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aSeekForwardAcrossTheMoveAfterASeekBackPlaysTheNewPictureAtOnce() = runTest {
        val harness = opened(movingPicture())
        harness.core.play()
        harness.run(6.seconds)
        assertIs<SeekResult.Applied>(harness.core.seek(Pts(2_000_000), SeekMode.Precise))
        harness.run(500.milliseconds)
        assertEquals(TrackId(0), harness.tracks.selectedVideo)

        val seen = checkNotNull(harness.renderer).presentations.size
        assertIs<SeekResult.Applied>(harness.core.seek(Pts(8_000_000), SeekMode.Precise))
        assertEquals(TrackId(5), harness.tracks.selectedVideo, "the seek remembered the move: ${harness.core.debugState}")
        harness.run(500.milliseconds)
        val shown = harness.shownUs.drop(seen)
        assertTrue(shown.isNotEmpty() && shown.first() in 8_000_000..8_040_000, "the seek showed the new picture: ${shown.take(3)}")
        assertEquals(
            listOf(TrackId(5), TrackId(0), TrackId(5)),
            harness.chosen.filter { it.kind == TrackKind.Video }.map { it.track },
        )
        assertEquals(1, harness.backend.openCalls, "without opening the item again")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aViewerWhoTurnedThePictureOffKeepsItOffAndCanChooseItInPlace() = runTest {
        val harness = opened(MediaScript(durationUs = 12_000_000, videoAppearsAtUs = 3_010_000))
        assertEquals(TrackChange.Applied(TrackKind.Video, null), harness.core.selectTrack(TrackKind.Video, null))
        assertEquals(1, harness.backend.openCalls, "turning off a picture that does not play changes nothing that plays")
        harness.core.play()
        harness.run(6.seconds)

        assertTrue(TrackId(0) in harness.added, "the picture is listed")
        assertEquals(null, harness.tracks.selectedVideo, "and does not play")
        assertEquals(emptyList(), harness.chosen)
        assertTrue(harness.shownUs.isEmpty())

        val opens = harness.backend.openCalls
        assertEquals(TrackChange.Applied(TrackKind.Video, TrackId(0)), harness.core.selectTrack(TrackKind.Video, TrackId(0)))
        harness.run(1.seconds)
        assertEquals(opens, harness.backend.openCalls, "the viewer's choice played in place")
        assertEquals(TrackId(0), harness.tracks.selectedVideo)
        val shown = harness.shownUs
        assertTrue(shown.isNotEmpty() && shown.first() in 6_000_000..6_100_000, "from the position: ${shown.take(3)}")
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun aLiveListenerCanTurnOffAPictureBeforeItAppears() = runTest {
        val harness = opened(liveWithLatePicture())
        // A source that cannot seek refuses a picture change that needs a rebuild, and this one needs none.
        assertEquals(TrackChange.Applied(TrackKind.Video, null), harness.core.selectTrack(TrackKind.Video, null))
        harness.core.play()
        harness.run(6.seconds)

        assertEquals(listOf(TrackId(0)), harness.added, "the picture is listed")
        assertEquals(null, harness.tracks.selectedVideo, "and does not play")
        assertEquals(emptyList(), harness.chosen)
        assertTrue(harness.shownUs.isEmpty())
        assertEquals(1, harness.backend.openCalls)
        harness.sink.audibleValues.clear()
        harness.run(1.seconds)
        assertTrue(harness.heardSound, "the sound plays on")
        // The picture's cache holds what the demux lane read for it, and the close lets all of it go.
        harness.close()
        assertEquals(0, harness.ledger.liveCount)
    }

    @Test
    fun mediaWhoseStreamsNeverChangeAnnouncesNothing() = runTest {
        val harness = opened(MediaScript(durationUs = 6_000_000))
        harness.core.play()
        harness.run(8.seconds)

        assertEquals(PlaybackStatus.Ended, harness.core.snapshots.value.status)
        assertEquals(emptyList(), harness.added)
        assertEquals(emptyList(), harness.chosen)
        assertEquals(1, harness.source.selectCalls)
        harness.close()
    }
}
