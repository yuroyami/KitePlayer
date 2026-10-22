package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A subtitle the player chose by itself is chosen again when the audio changes (#506).
 *
 * The media is an anime as it ships: Japanese audio by default and an English dub, a full English
 * track flagged default and an English forced track of signs. With no language preference the open
 * shows the full track, the dub takes the signs track made for it, and Japanese takes the full track
 * back. A subtitle anyone chose stays.
 */
class SubtitleFollowsAudioTest {

    private val japanese = TrackId(1)
    private val english = TrackId(2)
    private val full = TrackId(3)
    private val signs = TrackId(4)

    private fun cue(text: String): SubtitleCue = SubtitleCue.Text(0, 6_000_000, listOf(StyledSpan(text)))

    private fun anime() = MediaScript(
        durationUs = 6_000_000,
        hasAudio = false,
        additionalAudioTracks = listOf(
            ScriptedAudioTrack(index = 1, marker = 1f, language = "jpn", isDefault = true),
            ScriptedAudioTrack(index = 2, marker = 2f, language = "eng"),
        ),
        additionalSubtitleTracks = listOf(
            ScriptedSubtitleTrack(index = 3, cues = listOf(cue("every line")), language = "eng", isDefault = true),
            ScriptedSubtitleTrack(index = 4, cues = listOf(cue("A SIGN")), language = "eng", isForced = true),
        ),
    )

    private fun applied(change: TrackChange) = assertIs<TrackChange.Applied>(change, "the change was not applied: $change")

    private fun shownSubtitle(harness: CoreHarness): TrackId? = harness.core.snapshots.value.tracks.selectedSubtitle

    private fun cueTexts(harness: CoreHarness): List<String> =
        harness.core.subtitleCues.value.filterIsInstance<SubtitleCue.Text>().map { it.plainText }

    @Test
    fun aSubtitleThePlayerChoseFollowsTheAudio() = runTest {
        val harness = CoreHarness(this, script = anime())
        harness.openWithRenderer()
        assertEquals(japanese, harness.core.snapshots.value.tracks.selectedAudio)
        assertEquals(full, shownSubtitle(harness), "the open did not show the full track with the Japanese audio")
        harness.core.play()
        harness.run(1.seconds)

        // Read the moment the reply arrives, before the player does anything more: a caller resumed
        // on another thread reads at that moment, and must not see the dub beside every line.
        val atReply = withContext(Dispatchers.Unconfined) {
            applied(harness.core.selectTrack(TrackKind.Audio, english))
            shownSubtitle(harness)
        }
        assertEquals(signs, atReply, "the reply came before the subtitle that goes with the dub")
        assertEquals(signs, shownSubtitle(harness), "the dub kept every line instead of its signs track")
        harness.run(1.seconds)
        assertEquals(listOf("A SIGN"), cueTexts(harness), "the signs track was selected but its cues never showed")

        applied(harness.core.selectTrack(TrackKind.Audio, japanese))
        assertEquals(full, shownSubtitle(harness), "switching back did not bring the full track back")
        harness.run(1.seconds)
        assertEquals(listOf("every line"), cueTexts(harness))
        harness.close()
    }

    @Test
    fun aSubtitleTheViewerChoseStaysThroughEveryAudioChange() = runTest {
        val harness = CoreHarness(this, script = anime())
        harness.openWithRenderer()
        harness.core.play()
        // The same track the open chose, now chosen by the viewer, which makes it theirs.
        applied(harness.core.selectTrack(TrackKind.Subtitle, full))
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        assertEquals(full, shownSubtitle(harness), "the viewer's subtitle was replaced by the audio change")

        applied(harness.core.selectTrack(TrackKind.Subtitle, null))
        // Playing on between the changes, so the track switched away from has cached its packets again.
        harness.run(1.seconds)
        applied(harness.core.selectTrack(TrackKind.Audio, japanese))
        harness.run(1.seconds)
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        assertEquals(null, shownSubtitle(harness), "subtitles the viewer turned off came back with the audio")
        harness.close()
    }

    @Test
    fun aFileFlaggedToShowStaysThroughAnAudioChange() = runTest {
        val harness = CoreHarness(this, script = anime())
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://anime",
                externalSubtitles = listOf(
                    SubtitleSource(uri = "memory://Show.en.srt", selectImmediately = true, io = MediaIo.ofBytes(SRT.encodeToByteArray())),
                ),
            ),
        )
        val file = shownSubtitle(harness)
        assertTrue(file != null && file.value < 0, "the open did not show the flagged file: $file")
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        assertEquals(file, shownSubtitle(harness), "the flagged file was replaced by the audio change")
        harness.close()
    }

    @Test
    fun aFileAddedDuringPlaybackStaysThroughAnAudioChange() = runTest {
        val harness = CoreHarness(this, script = anime())
        harness.openWithRenderer()
        val added = harness.core.addExternalSubtitle(
            SubtitleSource(uri = "memory://Show.en.srt", io = MediaIo.ofBytes(SRT.encodeToByteArray())),
        )
        assertEquals(added, shownSubtitle(harness))
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        assertEquals(added, shownSubtitle(harness), "the added file was replaced by the audio change")
        harness.close()
    }

    @Test
    fun anAudioChangeThatRidesAVideoRebuildTakesTheSubtitleThatGoesWithIt() = runTest {
        val harness = CoreHarness(this, script = anime())
        harness.openWithRenderer()
        val opens = harness.backend.sessions.size
        // Both reach the actor before its next pass, so the audio rides the video's one reopen.
        val audio = async(start = CoroutineStart.UNDISPATCHED) { harness.core.selectTrack(TrackKind.Audio, english) }
        val video = async(start = CoroutineStart.UNDISPATCHED) { harness.core.selectTrack(TrackKind.Video, TrackId(0)) }
        applied(audio.await())
        applied(video.await())
        assertEquals(opens + 1, harness.backend.sessions.size, "the two changes did not share one reopen")
        assertEquals(english, harness.core.snapshots.value.tracks.selectedAudio)
        assertEquals(signs, shownSubtitle(harness), "the reopen kept every line under the dub")
        harness.close()
    }

    @Test
    fun aTrackShownAsTheSecondaryIsNeverTakenIntoThePrimarySlot() = runTest {
        val harness = CoreHarness(this, script = anime())
        harness.openWithRenderer()
        harness.core.play()
        applied(harness.core.selectSecondarySubtitle(signs))
        harness.run(1.seconds)
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        // The dub's own track is the viewer's secondary, so the primary stays as it was.
        assertEquals(full, shownSubtitle(harness), "the primary moved onto the secondary's track")
        assertEquals(signs, harness.core.snapshots.value.tracks.selectedSecondarySubtitle)
        harness.close()
    }

    @Test
    fun theNextItemOfAQueueChoosesItsOwnSubtitleAgain() = runTest {
        val harness = CoreHarness(this, script = anime())
        harness.attachRenderer()
        harness.core.openQueue(listOf(MediaItem("scripted://one"), MediaItem("scripted://two")), 0)
        harness.core.play()
        // The viewer's choice on the first item is theirs, and it ends with that item.
        applied(harness.core.selectTrack(TrackKind.Subtitle, full))
        var waited = 0
        while (harness.core.snapshots.value.queueIndex != 1 && waited++ < 2_000) harness.run(5.milliseconds)
        assertEquals(1, harness.core.snapshots.value.queueIndex, "the queue never moved on")
        assertEquals(
            emptyList(),
            harness.core.warningHistory().map { it.warning }.filterIsInstance<PlaybackWarning.GaplessFallback>(),
            "the second item did not take the preloaded path this test is about",
        )
        assertEquals(full, shownSubtitle(harness))
        harness.run(1.seconds)
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        assertEquals(signs, shownSubtitle(harness), "the second item kept the first item's choice as the viewer's")
        harness.close()
    }

    @Test
    fun aPreferredLanguageStillWinsWhateverTheAudio() = runTest {
        // mpv's default, subs-with-matching-audio=yes: a viewer who asked for English subtitles
        // keeps them under English audio too. A forced track wins only when the audio is foreign.
        val harness = CoreHarness(
            this,
            script = anime(),
            config = PlayerConfig(subtitles = SubtitleConfig(preferredLanguages = listOf("en"))),
        )
        harness.openWithRenderer()
        assertEquals(full, shownSubtitle(harness))
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        assertEquals(full, shownSubtitle(harness), "the preferred full track gave way under English audio")
        harness.close()
    }

    private fun preferringEnglish(setting: MatchingAudioSubtitles) =
        PlayerConfig(subtitles = SubtitleConfig(preferredLanguages = listOf("en"), withMatchingAudio = setting))

    @Test
    fun forcedOnlyShowsTheSignsUnderAudioTheViewerReadsAndEveryLineUnderAnyOther() = runTest {
        // mpv's subs-with-matching-audio=forced: English audio is audio this viewer understands.
        val harness = CoreHarness(this, script = anime(), config = preferringEnglish(MatchingAudioSubtitles.ForcedOnly))
        harness.openWithRenderer()
        harness.core.play()
        assertEquals(full, shownSubtitle(harness), "Japanese audio is not understood, so every line shows")
        harness.run(1.seconds)
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        assertEquals(signs, shownSubtitle(harness), "under English audio only the forced track may show")
        harness.run(1.seconds)
        applied(harness.core.selectTrack(TrackKind.Audio, japanese))
        assertEquals(full, shownSubtitle(harness))
        harness.close()
    }

    @Test
    fun noneShowsNoSubtitleUnderAudioTheViewerReads() = runTest {
        val harness = CoreHarness(this, script = anime(), config = preferringEnglish(MatchingAudioSubtitles.None))
        harness.openWithRenderer()
        harness.core.play()
        assertEquals(full, shownSubtitle(harness))
        harness.run(1.seconds)
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        assertEquals(null, shownSubtitle(harness), "a subtitle showed under audio the viewer reads")
        harness.run(1.seconds)
        applied(harness.core.selectTrack(TrackKind.Audio, japanese))
        assertEquals(full, shownSubtitle(harness), "the subtitles did not come back for the Japanese audio")
        // A subtitle the viewer asks for shows whatever the setting.
        applied(harness.core.selectTrack(TrackKind.Subtitle, full))
        harness.run(1.seconds)
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        assertEquals(full, shownSubtitle(harness), "the setting held back a subtitle the viewer chose")
        harness.close()
    }

    @Test
    fun forcedOnlyHoldsBackAFullFileUnderAudioTheViewerReads() = runTest {
        val harness = CoreHarness(this, script = anime(), config = preferringEnglish(MatchingAudioSubtitles.ForcedOnly))
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://anime",
                externalSubtitles = listOf(
                    SubtitleSource(uri = "memory://Show.en.srt", io = MediaIo.ofBytes(SRT.encodeToByteArray())),
                    SubtitleSource(uri = "memory://Show.en.forced.srt", io = MediaIo.ofBytes(SRT.encodeToByteArray())),
                ),
            ),
        )
        harness.core.play()
        val fullFile = TrackId(-1)
        val forcedFile = TrackId(-2)
        assertEquals(fullFile, shownSubtitle(harness), "the English file did not win under Japanese audio")
        harness.run(1.seconds)
        applied(harness.core.selectTrack(TrackKind.Audio, english))
        assertEquals(forcedFile, shownSubtitle(harness), "the full file stayed under English audio")
        harness.close()
    }

    @Test
    fun forcedOnlyChoosesTheForcedFileAtAnOpenIntoAudioTheViewerReads() = runTest {
        val config = preferringEnglish(MatchingAudioSubtitles.ForcedOnly).let {
            it.copy(audio = it.audio.copy(preferredLanguages = listOf("en")))
        }
        val harness = CoreHarness(this, script = anime(), config = config)
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://anime",
                externalSubtitles = listOf(
                    SubtitleSource(uri = "memory://Show.en.srt", io = MediaIo.ofBytes(SRT.encodeToByteArray())),
                    SubtitleSource(uri = "memory://Show.en.forced.srt", io = MediaIo.ofBytes(SRT.encodeToByteArray())),
                ),
            ),
        )
        assertEquals(english, harness.core.snapshots.value.tracks.selectedAudio)
        assertEquals(TrackId(-2), shownSubtitle(harness), "the open into English audio chose the full file")
        harness.close()
    }

    private companion object {
        const val SRT = "1\n00:00:00,500 --> 00:00:05,000\nFrom the file\n\n"
    }
}
