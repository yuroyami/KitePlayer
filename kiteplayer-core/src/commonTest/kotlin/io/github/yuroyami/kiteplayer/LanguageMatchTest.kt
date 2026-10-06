package io.github.yuroyami.kiteplayer

import io.github.yuroyami.kiteplayer.internal.LanguagePreferences
import io.github.yuroyami.kiteplayer.internal.LanguageTag
import io.github.yuroyami.kiteplayer.internal.pickAudioStream
import io.github.yuroyami.kiteplayer.internal.pickSubtitleStream
import io.github.yuroyami.kiteplayer.internal.sameLanguage
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A preferred language matches a track in any spelling of that language, and only that one (#435). */
class LanguageMatchTest {

    @Test
    fun everySpellingOfALanguageMeetsInOne() {
        val pairs = listOf(
            "ja" to "jpn", "ja" to "ja-JP", "JA" to "Jpn", "de" to "ger", "de" to "deu", "pt" to "por",
            "pt" to "pt-BR", "zh" to "chi", "zh" to "zho", "nl" to "dut", "cs" to "cze", "el" to "gre",
            "fr" to "fre", "fa" to "per", "he" to "iw", "id" to "in", "zh" to "zh-cmn-Hant", "en" to "en_US",
            "sq" to "alb", "my" to "bur", "ro" to "rum", "hy" to "arm", "ka" to "geo", "is" to "ice",
        )
        for ((a, b) in pairs) assertTrue(sameLanguage(a, b), "$a against $b")
    }

    @Test
    fun aLanguageDoesNotMeetItsNeighbours() {
        val pairs = listOf("en" to "enm", "fr" to "frm", "zh" to "yue", "zh" to "zh-yue", "no" to "nb", "ja" to "jav")
        for ((a, b) in pairs) assertFalse(sameLanguage(a, b), "$a against $b")
    }

    @Test
    fun codesThatNameNoLanguageMatchNothing() {
        for (code in listOf("und", "mul", "zxx", "mis", "qaa", "", "  ", null)) {
            assertNull(LanguageTag.parse(code), "'$code'")
            assertFalse(sameLanguage("en", code), "en against '$code'")
        }
    }

    @Test
    fun aTagIsReadIntoLanguageScriptAndRegion() {
        assertEquals(LanguageTag("zh", "Hant", "TW"), LanguageTag.parse("zh-hant-tw"))
        assertEquals(LanguageTag("es", null, "419"), LanguageTag.parse("es-419"))
        assertEquals(LanguageTag("yue", null, "HK"), LanguageTag.parse("zh-yue-HK"))
        assertEquals(LanguageTag("zh", null, null), LanguageTag.parse("cmn"))
        assertEquals("Hant", LanguageTag.parse("zh-HK")?.impliedScript)
        assertEquals("Hans", LanguageTag.parse("zh-CN")?.impliedScript)
    }

    @Test
    fun aRegionOrAScriptPicksTheCloserTrackOfOneLanguage() {
        val brazil = LanguagePreferences(listOf("pt-BR"))
        assertTrue(brazil.match("pt-BR", null)!!.closeness > brazil.match("pt-PT", null)!!.closeness)
        assertEquals(0, brazil.match("por", null)?.preference, "a bare language still answers a regional preference")

        val traditional = LanguagePreferences(listOf("zh-Hant"))
        val taiwan = traditional.match("zh-TW", null)!!
        val china = traditional.match("zh-CN", null)!!
        assertTrue(taiwan.closeness > china.closeness, "zh-TW is written in Traditional characters")
        val titled = traditional.match("chi", "繁體中文")!!
        val plain = traditional.match("chi", "简体中文")!!
        assertTrue(titled.closeness > plain.closeness, "the title names the script of a plain chi track")
        assertTrue(traditional.match("chi", "Chinese (Traditional)")!!.closeness > 0)
    }

    @Test
    fun aJapanesePreferenceFindsTheJpnSubtitlesAmongOthers() {
        val streams = listOf(subtitle(2, "eng", default = true), subtitle(3, "jpn"))
        val picked = pickSubtitleStream(streams, audio = null, SubtitleConfig(preferredLanguages = listOf("ja")))
        assertEquals(3, picked?.index)
    }

    @Test
    fun thePreferenceOrderDecidesBeforeTheDefaultFlag() {
        val streams = listOf(subtitle(2, "eng", default = true), subtitle(3, "jpn"))
        val picked = pickSubtitleStream(streams, audio = null, SubtitleConfig(preferredLanguages = listOf("ja", "en")))
        assertEquals(3, picked?.index)
    }

    @Test
    fun aRegionalSubtitlePreferenceTakesItsRegionFirst() {
        val streams = listOf(subtitle(2, "pt-PT"), subtitle(3, "pt-BR"), subtitle(4, "por"))
        assertEquals(3, pickSubtitleStream(streams, null, SubtitleConfig(preferredLanguages = listOf("pt-BR")))?.index)
        assertEquals(2, pickSubtitleStream(streams, null, SubtitleConfig(preferredLanguages = listOf("pt")))?.index)
    }

    @Test
    fun englishAudioFindsItsEngForcedTrack() {
        val streams = listOf(subtitle(2, "fre"), subtitle(3, "eng", forced = true))
        val picked = pickSubtitleStream(streams, audio = audio(1, "en"), SubtitleConfig())
        assertEquals(3, picked?.index)
    }

    @Test
    fun theForcedPairingFollowsTheAudioWhenNoPreferenceMatches() {
        val streams = listOf(subtitle(2, "fre"), subtitle(3, "eng", forced = true), subtitle(4, "fre", forced = true))
        val picked = pickSubtitleStream(
            streams,
            audio = audio(1, "en-US"),
            SubtitleConfig(preferredLanguages = listOf("ja"), autoSelect = false),
        )
        assertEquals(3, picked?.index)
    }

    @Test
    fun audioPrefersItsLanguageInAnySpellingAndKeepsOrdinaryTracksFirst() {
        val streams = listOf(
            audio(1, "eng", default = true),
            audio(2, "ger", accessibility = true),
            audio(3, "deu"),
        )
        assertEquals(3, pickAudioStream(streams, listOf("de"))?.index)
        assertEquals(1, pickAudioStream(streams, listOf("enm"))?.index, "Middle English is not English; the default plays")
    }

    @Test
    fun audioTakesTheRegionThePreferenceNames() {
        val streams = listOf(audio(1, "en-GB", default = true), audio(2, "en-US"))
        assertEquals(2, pickAudioStream(streams, listOf("en-US"))?.index)
        assertEquals(1, pickAudioStream(streams, listOf("en"))?.index)
    }

    private fun subtitle(index: Int, language: String, default: Boolean = false, forced: Boolean = false) =
        PlayerStreamInfo(index = index, kind = TrackKind.Subtitle, codec = "subrip", language = language, isDefault = default, isForced = forced)

    private fun audio(index: Int, language: String, default: Boolean = false, accessibility: Boolean = false) =
        PlayerStreamInfo(index = index, kind = TrackKind.Audio, codec = "aac", language = language, isDefault = default, isAccessibility = accessibility)
}
