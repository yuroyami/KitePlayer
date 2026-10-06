@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.subtitle.BitmapRegion
import io.github.yuroyami.kiteplayer.subtitle.RgbaBitmap
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
 * The forced pictures of a Blu-ray track (#513). The film has English, French and Japanese sound,
 * and an English and a French Blu-ray subtitle track that each hold one picture a second from one
 * second to five, every picture told apart by its width. English marks its pictures at two and four
 * seconds forced, and its picture at four seconds also holds a wide ordinary part beside the forced
 * one. French marks two, four and five. A disc's forced captions sit among the full subtitles in the
 * same track this way.
 */
class ForcedPicturesTest {

    private val english = TrackId(1)
    private val french = TrackId(2)
    private val japanese = TrackId(5)
    private val englishPictures = TrackId(3)
    private val frenchPictures = TrackId(4)

    private fun picture(second: Int, vararg parts: Pair<Int, Boolean>): SubtitleCue = SubtitleCue.Bitmap(
        startMicros = second * 1_000_000L,
        endMicros = second * 1_000_000L + 600_000L,
        regions = parts.map { (width, forced) ->
            BitmapRegion(0, 0, width, 1, 1920, 1080, RgbaBitmap(width, 1, ByteArray(width * 4)), forced = forced)
        },
    )

    private fun film(englishFlaggedForced: Boolean = false) = MediaScript(
        durationUs = 6_000_000,
        hasAudio = false,
        additionalAudioTracks = listOf(
            ScriptedAudioTrack(index = 1, marker = 1f, language = "eng", isDefault = true),
            ScriptedAudioTrack(index = 2, marker = 2f, language = "fra"),
            ScriptedAudioTrack(index = 5, marker = 5f, language = "jpn"),
        ),
        additionalSubtitleTracks = listOf(
            ScriptedSubtitleTrack(
                index = 3,
                language = "eng",
                isDefault = true,
                isForced = englishFlaggedForced,
                codec = "hdmv_pgs_subtitle",
                cues = listOf(
                    picture(1, 1 to false),
                    picture(2, 2 to true),
                    picture(3, 3 to false),
                    picture(4, 4 to true, 40 to false),
                    picture(5, 5 to false),
                ),
            ),
            ScriptedSubtitleTrack(
                index = 4,
                language = "fra",
                codec = "hdmv_pgs_subtitle",
                cues = listOf(
                    picture(1, 11 to false),
                    picture(2, 12 to true),
                    picture(3, 13 to false),
                    picture(4, 14 to true),
                    picture(5, 15 to true),
                ),
            ),
        ),
    )

    private fun TestScope.harness(
        subtitles: SubtitleConfig = SubtitleConfig(),
        script: MediaScript = film(),
    ) = CoreHarness(this, script = script, config = PlayerConfig(subtitles = subtitles))

    /** The widths of the pictures drawn now, which name them. */
    private fun drawn(harness: CoreHarness): List<Int> =
        harness.core.subtitleCues.value.filterIsInstance<SubtitleCue.Bitmap>().flatMap { cue -> cue.regions.map { it.width } }

    /** Plays on to 0.3 s past each of [seconds] in turn and says what was drawn there. */
    private suspend fun drawnAt(harness: CoreHarness, vararg seconds: Int): List<List<Int>> = seconds.map { second ->
        val target = second * 1_000_000L + 300_000L
        harness.run((target - harness.core.position().inWholeMicroseconds).coerceAtLeast(0).let { it / 1_000 }.milliseconds)
        drawn(harness)
    }

    private fun selected(harness: CoreHarness): TrackId? = harness.core.snapshots.value.tracks.selectedSubtitle

    private fun applied(change: TrackChange) = assertIs<TrackChange.Applied>(change, "the change was not applied: $change")

    @Test
    fun withBothOffEveryPictureOfTheSelectedTrackDraws() = runTest {
        val harness = harness()
        harness.openWithRenderer()
        assertEquals(englishPictures, selected(harness))
        harness.core.play()
        assertEquals(
            listOf(listOf(1), listOf(2), listOf(3), listOf(4, 40), listOf(5)),
            drawnAt(harness, 1, 2, 3, 4, 5),
        )
        harness.close()
    }

    @Test
    fun forcedOnlyDrawsTheForcedPicturesAndNothingElse() = runTest {
        val harness = harness(SubtitleConfig(forcedPicturesOnly = true))
        harness.openWithRenderer()
        assertTrue(harness.core.snapshots.value.forcedPicturesOnly)
        harness.core.play()
        // The ordinary part of the picture at four seconds goes and its forced part stays.
        assertEquals(
            listOf(emptyList(), listOf(2), emptyList(), listOf(4), emptyList()),
            drawnAt(harness, 1, 2, 3, 4, 5),
        )
        harness.close()
    }

    @Test
    fun forcedOnlyChangesWhilePlaying() = runTest {
        val harness = harness()
        val player = KitePlayer(harness.core)
        harness.openWithRenderer()
        harness.core.play()
        assertEquals(listOf(listOf(1)), drawnAt(harness, 1))
        player.setForcedPicturesOnly(true)
        assertEquals(listOf(listOf(2), emptyList()), drawnAt(harness, 2, 3))
        assertTrue(harness.core.snapshots.value.forcedPicturesOnly)
        player.setForcedPicturesOnly(false)
        harness.run(50.milliseconds)
        assertEquals(listOf(3), drawn(harness), "the picture on screen did not come back when the switch went off")
        assertEquals(false, harness.core.snapshots.value.forcedPicturesOnly)
        harness.close()
    }

    @Test
    fun aTrackFlaggedForcedDrawsWholeUnderForcedOnly() = runTest {
        val harness = harness(SubtitleConfig(forcedPicturesOnly = true), film(englishFlaggedForced = true))
        harness.openWithRenderer()
        applied(harness.core.selectTrack(TrackKind.Subtitle, englishPictures))
        harness.core.play()
        assertEquals(listOf(listOf(1), listOf(3)), drawnAt(harness, 1, 3), "the container says every picture is forced")
        harness.close()
    }

    @Test
    fun withSubtitlesOffNothingDrawsByDefault() = runTest {
        val harness = harness(SubtitleConfig(autoSelect = false))
        harness.openWithRenderer()
        assertEquals(null, selected(harness))
        harness.core.play()
        assertEquals(List(5) { emptyList<Int>() }, drawnAt(harness, 1, 2, 3, 4, 5))
        harness.close()
    }

    @Test
    fun withSubtitlesOffTheForcedPicturesOfTheAudiosLanguageDraw() = runTest {
        val harness = harness(SubtitleConfig(autoSelect = false, forcedPicturesWhenOff = true))
        harness.openWithRenderer()
        harness.core.play()
        assertEquals(
            listOf(emptyList(), listOf(2), emptyList(), listOf(4), emptyList()),
            drawnAt(harness, 1, 2, 3, 4, 5),
        )
        assertEquals(null, selected(harness), "the track the forced pictures come from is not a selection")
        harness.close()
    }

    @Test
    fun theForcedPicturesFollowTheAudio() = runTest {
        val harness = harness(SubtitleConfig(autoSelect = false, forcedPicturesWhenOff = true))
        harness.openWithRenderer()
        harness.core.play()
        assertEquals(listOf(listOf(2)), drawnAt(harness, 2))
        applied(harness.core.selectTrack(TrackKind.Audio, french))
        assertEquals(listOf(emptyList(), listOf(14)), drawnAt(harness, 3, 4), "the French sound did not bring the French captions")
        applied(harness.core.selectTrack(TrackKind.Audio, japanese))
        assertEquals(listOf(emptyList<Int>()), drawnAt(harness, 5), "no track is in Japanese, so nothing draws")
        assertEquals(null, selected(harness))
        harness.close()
    }

    @Test
    fun selectingASubtitleEndsTheForcedPicturesAndTurningItOffBringsThemBack() = runTest {
        val harness = harness(SubtitleConfig(autoSelect = false, forcedPicturesWhenOff = true))
        harness.openWithRenderer()
        harness.core.play()
        assertEquals(listOf(listOf(2)), drawnAt(harness, 2))
        // The very track the forced pictures came from, now chosen, so all of it draws.
        applied(harness.core.selectTrack(TrackKind.Subtitle, englishPictures))
        assertEquals(englishPictures, selected(harness))
        assertEquals(listOf(listOf(3)), drawnAt(harness, 3))
        applied(harness.core.selectTrack(TrackKind.Subtitle, null))
        assertEquals(null, selected(harness))
        assertEquals(listOf(listOf(4), emptyList()), drawnAt(harness, 4, 5))
        harness.close()
    }

    @Test
    fun selectingAnotherTrackTakesTheLaneAndOffGivesItBack() = runTest {
        val harness = harness(SubtitleConfig(autoSelect = false, forcedPicturesWhenOff = true))
        harness.openWithRenderer()
        harness.core.play()
        assertEquals(listOf(listOf(2)), drawnAt(harness, 2))
        applied(harness.core.selectTrack(TrackKind.Subtitle, frenchPictures))
        assertEquals(listOf(listOf(13)), drawnAt(harness, 3), "the French track drew only some of its pictures")
        applied(harness.core.selectTrack(TrackKind.Subtitle, null))
        assertEquals(listOf(listOf(4)), drawnAt(harness, 4), "the English forced pictures did not come back")
        harness.close()
    }

    @Test
    fun aSecondarySubtitleIsASelectionToo() = runTest {
        val harness = harness(SubtitleConfig(autoSelect = false, forcedPicturesWhenOff = true))
        harness.openWithRenderer()
        harness.core.play()
        assertEquals(listOf(listOf(2)), drawnAt(harness, 2))
        // The forced pictures' own track, which one lane must let go of before the other reads it.
        applied(harness.core.selectSecondarySubtitle(englishPictures))
        assertEquals(listOf(listOf(3)), drawnAt(harness, 3), "the secondary drew less than all of its pictures, or twice")
        assertEquals(null, selected(harness))
        applied(harness.core.selectSecondarySubtitle(null))
        assertEquals(listOf(listOf(4)), drawnAt(harness, 4))
        harness.close()
    }

    @Test
    fun aFileAddedWhileOffTakesThePlaceOfTheForcedPictures() = runTest {
        val harness = harness(SubtitleConfig(autoSelect = false, forcedPicturesWhenOff = true))
        harness.openWithRenderer()
        harness.core.play()
        assertEquals(listOf(listOf(2)), drawnAt(harness, 2))
        val file = harness.core.addExternalSubtitle(
            SubtitleSource(uri = "memory://Film.en.srt", io = MediaIo.ofBytes(SRT.encodeToByteArray())),
        )
        assertEquals(file, selected(harness))
        drawnAt(harness, 4)
        val cues = harness.core.subtitleCues.value
        assertEquals(listOf("From the file"), cues.filterIsInstance<SubtitleCue.Text>().map { it.plainText })
        assertEquals(emptyList(), drawn(harness), "a forced picture drew beside the file")
        harness.close()
    }

    @Test
    fun theForcedPicturesComeBackAfterASeek() = runTest {
        val harness = harness(SubtitleConfig(autoSelect = false, forcedPicturesWhenOff = true))
        harness.openWithRenderer()
        harness.core.play()
        assertEquals(listOf(listOf(4)), drawnAt(harness, 4))
        harness.core.seek(Pts(2_100_000), SeekMode.Precise)
        harness.run(100.milliseconds)
        assertEquals(listOf(2), drawn(harness), "the seek back lost the forced picture at two seconds")
        harness.close()
    }

    private companion object {
        const val SRT = "1\n00:00:00,500 --> 00:00:05,500\nFrom the file\n\n"
    }
}
