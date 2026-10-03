package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.SubtitleConfig
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.TrackInfo
import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a subtitle file's name says, and when a preferred-language file beats the container's track (#514). */
class SubtitleFileNamesTest {

    private fun hints(name: String) = subtitleNameHints(name).let { Triple(it.language, it.forced, it.hearingImpaired) }

    @Test
    fun aLanguageCodeBeforeTheExtensionNamesTheLanguage() {
        assertEquals(Triple("en", false, false), hints("Film.en.srt"))
        assertEquals(Triple("eng", true, false), hints("Film.eng.forced.srt"))
        assertEquals(Triple("pt-BR", false, true), hints("Film.pt-BR.sdh.srt"))
        assertEquals(Triple("zh-Hant", false, false), hints("Film.zh-Hant.ass"))
        assertEquals(Triple("FR", false, false), hints("Film.FR.srt"))
        assertEquals(Triple("en", true, true), hints("The.Film.2019.1080p.en.cc.forced.srt"))
    }

    @Test
    fun aNameThatSaysNoLanguageGivesNone() {
        assertEquals(Triple(null, false, false), hints("Film.2019.srt"))
        assertEquals(Triple(null, false, false), hints("Film.srt"))
        assertEquals(Triple(null, false, false), hints("subtitles"))
        // A title word that happens to be a code, in title case, is a word: Pi is not Pali.
        assertEquals(Triple(null, false, false), hints("Life.of.Pi.srt"))
        // Two or three letters that are no language this table knows, such as an encoder's name.
        assertEquals(Triple(null, false, false), hints("Film.x264.srt"))
        assertEquals(Triple(null, false, false), hints("Film.web.srt"))
        // The first part is always the title, however it is spelled.
        assertEquals(Triple(null, false, false), hints("en.srt"))
    }

    @Test
    fun hiIsHindiAloneAndAHearingImpairedFlagBesideALanguage() {
        assertEquals(Triple("hi", false, false), hints("Film.hi.srt"))
        assertEquals(Triple("en", false, true), hints("Film.en.hi.srt"))
        assertEquals(Triple("en", false, true), hints("Film.hi.en.srt"))
    }

    @Test
    fun anAddressIsReadByItsLastSegmentWithoutQueryOrFragment() {
        assertEquals("ja", subtitleNameHints("https://cdn.test/subs/Film.ja.vtt?token=a.b.c#t=1").language)
        assertEquals("de", subtitleNameHints("C:\\Subs\\Film.de.srt").language)
        assertNull(subtitleNameHints("https://cdn.test/Film.ja/subs.srt").language)
    }

    private fun external(id: Int, language: String?, forced: Boolean = false) =
        TrackInfo(TrackId(-id), TrackKind.Subtitle, "external/subrip", language = language, isForced = forced)

    private fun container(language: String?) = PlayerStreamInfo(index = 2, kind = TrackKind.Subtitle, codec = "subrip", language = language)

    @Test
    fun aPreferredLanguageFileBeatsAContainerTrackInALaterOneOrNone() {
        val config = SubtitleConfig(preferredLanguages = listOf("ja", "en"))
        val japanese = external(1, "ja")
        assertEquals(japanese, preferredExternalSubtitle(null, listOf(japanese), config))
        assertEquals(japanese, preferredExternalSubtitle(container("eng"), listOf(japanese), config))
        // The same language: the file the caller added wins the tie.
        assertEquals(japanese, preferredExternalSubtitle(container("jpn"), listOf(japanese), config))
        // A container track in a better language keeps its place.
        assertNull(preferredExternalSubtitle(container("jpn"), listOf(external(1, "en")), config))
        // A file in no preferred language, or with no language, never wins.
        assertNull(preferredExternalSubtitle(null, listOf(external(1, "fr"), external(2, null)), config))
        // With no preferences the container's choice stands.
        assertNull(preferredExternalSubtitle(null, listOf(japanese), SubtitleConfig()))
        // Of two files in the language, the one that is not forced.
        val forced = external(1, "ja", forced = true)
        val full = external(2, "ja")
        assertEquals(full, preferredExternalSubtitle(null, listOf(forced, full), config))
    }

    @Test
    fun onlyKnownCodesCount() {
        assertTrue(LanguageTag.isKnownCode("en"))
        assertTrue(LanguageTag.isKnownCode("eng"))
        assertTrue(LanguageTag.isKnownCode("ger"))
        assertFalse(LanguageTag.isKnownCode("web"))
        assertFalse(LanguageTag.isKnownCode("xx"))
    }
}
