package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
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
