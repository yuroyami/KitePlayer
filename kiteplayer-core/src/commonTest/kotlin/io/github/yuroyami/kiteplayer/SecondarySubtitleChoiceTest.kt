@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.placeSecondaryCues
import io.github.yuroyami.kiteplayer.internal.secondaryFirst
import io.github.yuroyami.kiteplayer.subtitle.CueAlignment
import io.github.yuroyami.kiteplayer.subtitle.CueLayout
import io.github.yuroyami.kiteplayer.subtitle.CueStacking
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The second subtitle track the configuration asks for (#494): chosen by language at each open, as
 * mpv's `secondary-slang` chooses it, and drawn at the top or stacked directly above or below the
 * primary track.
 *
 * The film carries an English track, the primary, and Japanese, French and Japanese picture tracks
 * beside it, every line running from half a second to four.
 */
class SecondarySubtitleChoiceTest {

    private fun cue(text: String, layout: CueLayout = CueLayout()): SubtitleCue =
        SubtitleCue.Text(500_000, 4_000_000, listOf(StyledSpan(text)), layout = layout)

    private val japanese = TrackId(3)
    private val french = TrackId(4)
    private val japanesePictures = TrackId(5)

    private fun film(withJapaneseText: Boolean = true) = MediaScript(
        durationUs = 6_000_000,
        subtitleCues = listOf(cue("english line")),
        additionalSubtitleTracks = listOfNotNull(
            ScriptedSubtitleTrack(index = 3, cues = listOf(cue("japanese line")), language = "jpn")
                .takeIf { withJapaneseText },
            ScriptedSubtitleTrack(index = 4, cues = listOf(cue("french line")), language = "fra"),
            ScriptedSubtitleTrack(
                index = 5,
                cues = listOf(cue("japanese picture")),
                language = "jpn",
                codec = "hdmv_pgs_subtitle",
            ),
        ),
    )

    private suspend fun TestScope.opened(
        secondaryLanguages: List<String>,
        placement: SecondarySubtitlePlacement = SecondarySubtitlePlacement.Top,
        script: MediaScript = film(),
    ): CoreHarness {
        val harness = CoreHarness(
            this,
            script = script,
            config = PlayerConfig(
                subtitles = SubtitleConfig(
                    preferredLanguages = listOf("en"),
                    secondaryLanguages = secondaryLanguages,
                    secondaryPlacement = placement,
                ),
            ),
        )
        harness.openWithRenderer()
        return harness
    }

    private fun CoreHarness.secondary(): TrackId? = core.snapshots.value.tracks.selectedSecondarySubtitle

    private fun CoreHarness.showing(): List<SubtitleCue.Text> = core.subtitleCues.value.filterIsInstance<SubtitleCue.Text>()

    @Test
    fun theSecondaryLanguageIsSelectedAtOpen() = runTest {
        val harness = opened(listOf("ja"))
        assertEquals(TrackId(2), harness.core.snapshots.value.tracks.selectedSubtitle)
        assertEquals(japanese, harness.secondary(), "the Japanese track was not selected beside the English")
        val opened = harness.events.filterIsInstance<PlayerEvent.Opened>().single()
        assertEquals(japanese, opened.tracks.selectedSecondarySubtitle, "the open did not report the secondary it chose")
        harness.core.play()
        harness.run(1_000.milliseconds)
        assertEquals(listOf("english line", "japanese line"), harness.showing().map { it.plainText })
        harness.close()
    }

    @Test
    fun theSecondaryIsNeverThePrimaryAndTheListIsReadInOrder() = runTest {
        val harness = opened(listOf("en", "fr", "ja"))
        assertEquals(french, harness.secondary(), "the second language was passed over for the primary's or a later one")
        harness.close()
    }

    @Test
    fun aPictureTrackIsNotChosenAsTheSecondary() = runTest {
        val harness = opened(listOf("ja"), script = film(withJapaneseText = false))
        assertEquals(null, harness.secondary(), "a picture track was chosen to sit beside the primary")
        harness.close()
    }

    @Test
    fun noSecondaryLanguageSelectsNoSecondary() = runTest {
        val harness = opened(emptyList())
        assertEquals(null, harness.secondary())
        harness.close()
    }

    @Test
    fun aSecondaryAboveThePrimaryJoinsTheBottomStackAfterIt() = runTest {
        val harness = opened(listOf("ja"), SecondarySubtitlePlacement.AbovePrimary)
        harness.core.play()
        harness.run(1_000.milliseconds)
        val showing = harness.showing()
        assertEquals(listOf("english line", "japanese line"), showing.map { it.plainText })
        assertEquals(CueAlignment.BottomCenter, showing.last().layout.alignment, "the secondary did not join the bottom stack")
        harness.close()
    }

    @Test
    fun aSecondaryBelowThePrimaryTakesTheBottomOfTheStack() = runTest {
        val harness = opened(listOf("ja"), SecondarySubtitlePlacement.BelowPrimary)
        harness.core.play()
        harness.run(1_000.milliseconds)
        val showing = harness.showing()
        assertEquals(listOf("japanese line", "english line"), showing.map { it.plainText })
        assertEquals(CueAlignment.BottomCenter, showing.first().layout.alignment, "the secondary did not join the bottom stack")
        harness.close()
    }

    @Test
    fun theTopPlacementKeepsTheSecondaryApart() = runTest {
        val harness = opened(listOf("ja"))
        harness.core.play()
        harness.run(1_000.milliseconds)
        assertEquals(CueAlignment.TopCenter, harness.showing().last().layout.alignment)
        harness.close()
    }

    /**
     * An ASS script with reverse collisions piles its newest line at the bottom, so below the primary
     * is last in the list there, and the secondary takes that stacking so the one pile grows one way.
     */
    @Test
    fun aReversedPrimaryStackTurnsTheOrderAround() {
        val primary = listOf(cue("primary", CueLayout(stacking = CueStacking.LastAtBottom)))
        val secondary = listOf(cue("secondary", CueLayout(alignment = CueAlignment.MiddleLeft, positionY = 0.3f)))
        val placed = placeSecondaryCues(secondary, primary, SecondarySubtitlePlacement.BelowPrimary)
            .single() as SubtitleCue.Text
        assertEquals(CueStacking.LastAtBottom, placed.layout.stacking)
        assertEquals(CueAlignment.BottomCenter, placed.layout.alignment)
        assertEquals(null, placed.layout.positionY, "the secondary kept its author's place")
        assertTrue(!secondaryFirst(primary, SecondarySubtitlePlacement.BelowPrimary))
        assertTrue(secondaryFirst(primary, SecondarySubtitlePlacement.AbovePrimary))
        val plain = listOf(cue("primary"))
        assertTrue(secondaryFirst(plain, SecondarySubtitlePlacement.BelowPrimary))
        assertTrue(!secondaryFirst(plain, SecondarySubtitlePlacement.AbovePrimary))
        assertTrue(!secondaryFirst(plain, SecondarySubtitlePlacement.Top))
    }
}
