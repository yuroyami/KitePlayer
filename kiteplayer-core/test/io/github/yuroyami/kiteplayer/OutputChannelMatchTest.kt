package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.pickAudioStream
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * One language mixed twice, in 5.1 and in stereo, and the mix that suits the speakers (#466).
 */
class OutputChannelMatchTest {

    private fun audio(
        index: Int,
        language: String?,
        channels: Int?,
        default: Boolean = false,
        accessibility: Boolean = false,
        commentary: Boolean = false,
        title: String? = null,
    ) = PlayerStreamInfo(
        index = index,
        kind = TrackKind.Audio,
        codec = "aac",
        language = language,
        title = title,
        isDefault = default,
        isAccessibility = accessibility,
        channels = channels,
        isCommentary = commentary,
    )

    private val film = listOf(audio(1, "eng", 6, default = true), audio(2, "eng", 2))

    @Test
    fun twoSpeakersTakeTheStereoMixAndSixTakeTheSurround() {
        assertEquals(2, pickAudioStream(film, emptyList(), outputChannels = 2)?.index)
        assertEquals(1, pickAudioStream(film, emptyList(), outputChannels = 6)?.index)
        assertEquals(1, pickAudioStream(film, emptyList(), outputChannels = 8)?.index)
    }

    @Test
    fun withNoAnswerTheDefaultPlaysAsBefore() {
        assertEquals(1, pickAudioStream(film, emptyList(), outputChannels = null)?.index)
        assertEquals(1, pickAudioStream(film, emptyList())?.index)
    }

    @Test
    fun aCommentaryNeverStandsInForTheMainMix() {
        val flagged = listOf(audio(1, "eng", 6, default = true), audio(2, "eng", 2, commentary = true))
        assertEquals(1, pickAudioStream(flagged, emptyList(), outputChannels = 2)?.index)
        val titled = listOf(audio(1, "eng", 6, default = true), audio(2, "eng", 2, title = "Director's Commentary"))
        assertEquals(1, pickAudioStream(titled, emptyList(), outputChannels = 2)?.index)
    }

    @Test
    fun onlyTheSameLanguageAndAccessibilityCanStandIn() {
        val otherLanguage = listOf(audio(1, "eng", 6, default = true), audio(2, "fre", 2))
        assertEquals(1, pickAudioStream(otherLanguage, emptyList(), outputChannels = 2)?.index)
        val described = listOf(audio(1, "eng", 6, default = true), audio(2, "eng", 2, accessibility = true))
        assertEquals(1, pickAudioStream(described, emptyList(), outputChannels = 2)?.index)
        val spelledOtherwise = listOf(audio(1, "en", 6, default = true), audio(2, "eng", 2))
        assertEquals(2, pickAudioStream(spelledOtherwise, emptyList(), outputChannels = 2)?.index)
    }

    @Test
    fun thePreferredLanguageChoosesFirstAndTheSpeakersChooseWithinIt() {
        val streams = listOf(audio(1, "eng", 6, default = true), audio(2, "fre", 6), audio(3, "fra", 2))
        assertEquals(3, pickAudioStream(streams, listOf("fr"), outputChannels = 2)?.index)
        assertEquals(2, pickAudioStream(streams, listOf("fr"), outputChannels = 6)?.index)
    }

    @Test
    fun aTieKeepsTheUsualChoiceAndAnUncountedTrackNeverStandsIn() {
        val tie = listOf(audio(1, "eng", 6, default = true), audio(2, "eng", 8))
        assertEquals(1, pickAudioStream(tie, emptyList(), outputChannels = 7)?.index)
        val uncounted = listOf(audio(1, "eng", 6, default = true), audio(2, "eng", null))
        assertEquals(1, pickAudioStream(uncounted, emptyList(), outputChannels = 2)?.index)
        val untagged = listOf(audio(1, null, 6, default = true), audio(2, null, 2), audio(3, "eng", 2))
        assertEquals(2, pickAudioStream(untagged, emptyList(), outputChannels = 2)?.index)
    }

    @Test
    fun theOpenAsksTheOutputOnlyWhenTheSettingIsOn() = runTest {
        val script = MediaScript(
            channels = 6,
            additionalAudioTracks = listOf(
                ScriptedAudioTrack(index = 2, marker = 2f, language = "eng", title = "Stereo", channels = 2),
            ),
        )
        val on = CoreHarness(this, script = script, config = PlayerConfig(audio = AudioConfig(matchOutputChannels = true)))
        on.output.outputChannels = 2
        on.openWithRenderer()
        assertEquals(TrackId(2), on.core.snapshots.value.tracks.selectedAudio)
        on.close()

        val off = CoreHarness(this, script = script)
        off.output.outputChannels = 2
        off.openWithRenderer()
        assertEquals(TrackId(1), off.core.snapshots.value.tracks.selectedAudio)
        assertEquals(0, off.output.outputChannelQuestions)
        off.close()
    }
}
