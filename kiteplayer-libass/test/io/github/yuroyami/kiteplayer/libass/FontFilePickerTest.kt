package io.github.yuroyami.kiteplayer.libass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scan of a font directory hands libass the platform's Latin sans face first, because libass
 * takes its default family from the first font it is given, and then the faces that cover other
 * scripts, inside the byte budget (#507).
 */
class FontFilePickerTest {

    private val megabyte = 1024L * 1024

    private fun file(name: String, megabytes: Double) = FontFileCandidate(name, (megabytes * megabyte).toLong(), "/fonts/$name")

    private fun pick(files: List<FontFileCandidate>, budgetMegabytes: Long = 24): List<String> =
        pickFontFiles(files, budgetMegabytes * megabyte).map { it.name }

    /** A cut of an Android 14 `/system/fonts`, with the sizes the files have there. */
    private val android = listOf(
        file("AndroidClock.ttf", 0.005),
        file("CarroisGothicSC-Regular.ttf", 0.05),
        file("ComingSoon.ttf", 0.06),
        file("CutiveMono.ttf", 0.07),
        file("DroidSansMono.ttf", 0.12),
        file("NotoColorEmoji.ttf", 10.0),
        file("NotoNaskhArabic-Regular.ttf", 0.15),
        file("NotoSansAdlam-VF.ttf", 0.06),
        file("NotoSansArmenian-VF.ttf", 0.06),
        file("NotoSansCJK-Regular.ttc", 19.0),
        file("NotoSansHebrew-Regular.ttf", 0.03),
        file("NotoSansSymbols-Regular-Subsetted.ttf", 0.1),
        file("NotoSansThai-Regular.ttf", 0.03),
        file("NotoSerif-Bold.ttf", 0.4),
        file("NotoSerif-Regular.ttf", 0.4),
        file("NotoSerifCJK-Regular.ttc", 25.0),
        file("Roboto-Regular.ttf", 1.0),
        file("RobotoFlex-Regular.ttf", 1.6),
        file("RobotoStatic-Regular.ttf", 0.17),
        file("SourceSansPro-Regular.ttf", 0.3),
        file("fonts.xml", 0.04),
    )

    @Test
    fun robotoComesFirstOnAndroidAheadOfEveryNotoFileThatSortsBeforeIt() {
        val picked = pick(android)
        assertEquals("Roboto-Regular.ttf", picked.first())
        assertEquals("RobotoStatic-Regular.ttf", picked[1], "the static cut of the same family comes next")
        assertTrue(picked.indexOf("AndroidClock.ttf") > picked.indexOf("NotoSansThai-Regular.ttf"), "picked: $picked")
    }

    @Test
    fun oneEastAsianFaceComesRightAfterTheLatinSansAndFitsTheBudget() {
        val picked = pick(android)
        assertEquals("NotoSansCJK-Regular.ttc", picked[2], "picked: $picked")
        assertTrue("NotoSerifCJK-Regular.ttc" !in picked, "two East Asian faces were taken: $picked")
    }

    @Test
    fun theScriptsSubtitlesUseMostComeBeforeTheOtherNotoScripts() {
        val picked = pick(android)
        val scripts = picked.filter { it.startsWith("NotoNaskh") || it.startsWith("NotoSansA") || it.startsWith("NotoSansH") || it.startsWith("NotoSansT") }
        assertEquals(
            listOf("NotoNaskhArabic-Regular.ttf", "NotoSansHebrew-Regular.ttf", "NotoSansThai-Regular.ttf", "NotoSansArmenian-VF.ttf", "NotoSansAdlam-VF.ttf"),
            scripts,
        )
    }

    @Test
    fun emojiAndSymbolFacesGoLastAndAFileTooBigForWhatIsLeftIsSkipped() {
        val picked = pick(android)
        assertTrue("NotoColorEmoji.ttf" !in picked, "ten megabytes of emoji fitted into the five left: $picked")
        assertEquals(listOf("AndroidClock.ttf", "NotoSansSymbols-Regular-Subsetted.ttf"), picked.takeLast(2))
        assertTrue("fonts.xml" !in picked)
        val everything = pick(android, budgetMegabytes = 1024)
        assertEquals(listOf("AndroidClock.ttf", "NotoColorEmoji.ttf", "NotoSansSymbols-Regular-Subsetted.ttf"), everything.takeLast(3))
    }

    @Test
    fun aLinuxDesktopGetsItsFirstSansFamilyAndItsChineseFace() {
        val linux = listOf(
            file("DejaVuSans-Bold.ttf", 0.7),
            file("DejaVuSans.ttf", 0.75),
            file("DejaVuSansMono.ttf", 0.34),
            file("DejaVuSerif.ttf", 0.38),
            file("FreeSans.ttf", 0.7),
            file("LiberationSans-Regular.ttf", 0.4),
            file("NotoColorEmoji.ttf", 10.0),
            file("opens___.ttf", 0.02),
            file("wqy-zenhei.ttc", 16.0),
            file("map-ISO8859-1", 0.01),
        )
        val picked = pick(linux)
        assertEquals(listOf("DejaVuSans.ttf", "LiberationSans-Regular.ttf", "FreeSans.ttf", "wqy-zenhei.ttc"), picked.take(4))
        assertTrue(picked.indexOf("DejaVuSans-Bold.ttf") > picked.indexOf("wqy-zenhei.ttc"), "picked: $picked")
    }

    @Test
    fun notoSansWinsOverDejaVuAndItsBoldWaitsForTheOtherScripts() {
        val picked = pick(
            listOf(
                file("DejaVuSans.ttf", 0.75),
                file("NotoSans-Bold.ttf", 0.5),
                file("NotoSans-Regular.ttf", 0.5),
                file("NotoSansArabic-Regular.ttf", 0.2),
                file("NotoSansCJK-Regular.ttc", 19.0),
            ),
        )
        assertEquals(
            listOf("NotoSans-Regular.ttf", "DejaVuSans.ttf", "NotoSansCJK-Regular.ttc", "NotoSansArabic-Regular.ttf", "NotoSans-Bold.ttf"),
            picked,
        )
    }

    @Test
    fun anEmptyOrUnreadableFileIsNeverPicked() {
        assertEquals(listOf("Roboto-Regular.ttf"), pick(listOf(file("Broken.ttf", 0.0), file("Roboto-Regular.ttf", 1.0))))
    }
}
