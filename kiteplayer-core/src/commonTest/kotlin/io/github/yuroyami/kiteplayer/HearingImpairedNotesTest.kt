@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.hideHearingImpairedNotes
import io.github.yuroyami.kiteplayer.subtitle.CueStyle
import io.github.yuroyami.kiteplayer.subtitle.StyledSpan
import io.github.yuroyami.kiteplayer.subtitle.SubtitleCue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The notes of hearing-impaired subtitles can be hidden, and only when asked (#493). */
class HearingImpairedNotesTest {

    private fun cue(vararg spans: StyledSpan) = SubtitleCue.Text(0, 1_000_000, spans.toList())

    private fun cue(text: String) = cue(StyledSpan(text))

    private fun texts(cues: List<SubtitleCue>, mode: HearingImpairedNotes): List<String> =
        hideHearingImpairedNotes(cues, mode).map { (it as SubtitleCue.Text).spans.joinToString("") { span -> span.text } }

    @Test
    fun theNotesGoAndTheDialogueStays() {
        val cues = listOf(
            cue("[MUSIC]"), cue("(laughs) Hello"), cue("JOHN: Hi"), cue("♪ la la ♪"), cue("A plain line"),
            cue("- [GASPS]\n- What?"), cue("MAN #2: Over here"), cue("[door slams] Who is it?"),
        )
        assertEquals(
            listOf("Hello", "Hi", "A plain line", "- What?", "Over here", "Who is it?"),
            texts(cues, HearingImpairedNotes.Hide),
        )
    }

    @Test
    fun keepLeavesEveryCueAsItWas() {
        val cues = listOf(cue("[MUSIC]"), cue("JOHN: Hi"))
        assertSame(cues, hideHearingImpairedNotes(cues, HearingImpairedNotes.Keep))
    }

    @Test
    fun onlyTheStrictLevelTakesAParenthesisWithinALine() {
        val cues = listOf(cue("I said (quietly) no"), cue("(sighs)"))
        assertEquals(listOf("I said (quietly) no"), texts(cues, HearingImpairedNotes.Hide))
        assertEquals(listOf("I said no"), texts(cues, HearingImpairedNotes.HideStrict))
    }

    @Test
    fun aNameMustBeInCapitalLettersThatHaveACase() {
        val cues = listOf(cue("Note: this stays"), cue("وووو و: نعم"), cue("שלום: כן"), cue("10:30 sharp"), cue("A: one letter"))
        assertEquals(listOf("Note: this stays", "وووو و: نعم", "שלום: כן", "10:30 sharp", "A: one letter"), texts(cues, HearingImpairedNotes.Hide))
    }

    @Test
    fun fullWidthBracketsCount() {
        val cues = listOf(cue("［ドアの音］こんにちは"), cue("（笑）はい"), cue("【音楽】"))
        assertEquals(listOf("こんにちは", "はい"), texts(cues, HearingImpairedNotes.Hide))
    }

    @Test
    fun theSpansKeepTheirStyles() {
        val italic = CueStyle(italic = true)
        val bold = CueStyle(bold = true)
        val filtered = hideHearingImpairedNotes(
            listOf(cue(StyledSpan("(laughs) ", italic), StyledSpan("Hello ", CueStyle()), StyledSpan("there", bold))),
            HearingImpairedNotes.Hide,
        ).single() as SubtitleCue.Text
        assertEquals(listOf("Hello ", "there"), filtered.spans.map { it.text })
        assertEquals(listOf(CueStyle(), bold), filtered.spans.map { it.style })
    }

    private val sdhSrt = "1\n00:00:00,500 --> 00:00:01,500\n[MUSIC]\n\n" +
        "2\n00:00:02,000 --> 00:00:03,000\nJOHN: Hi\n\n"

    @Test
    fun anExternalFileShowsOnlyItsDialogueWithTheSettingOn() = runTest {
        val harness = CoreHarness(
            this,
            script = MediaScript(durationUs = 10_000_000),
            config = PlayerConfig(subtitles = SubtitleConfig(hearingImpairedNotes = HearingImpairedNotes.Hide)),
        )
        harness.attachRenderer()
        harness.core.open(
            MediaItem(
                "scripted://one",
                externalSubtitles = listOf(
                    SubtitleSource(uri = "memory://Film.en.srt", selectImmediately = true, io = MediaIo.ofBytes(sdhSrt.encodeToByteArray())),
                ),
            ),
        )
        harness.core.play()
        harness.run(4.seconds)
        val drawn = harness.output.rasterizedCueTexts.flatten()
        assertTrue("Hi" in drawn, "the dialogue was not drawn: $drawn")
        assertTrue(drawn.none { "MUSIC" in it || "JOHN" in it }, "a note was drawn: $drawn")
        harness.close()
    }
}
