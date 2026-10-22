package io.github.yuroyami.kiteplayer.subtitle

import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.END_BOX
import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.START_BOX
import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.boxed
import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.enhancements
import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.header
import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.magazineSets
import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.pageSets
import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.payload
import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.row
import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.stuffing
import io.github.yuroyami.kiteplayer.subtitle.TeletextUnits.triplet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The teletext page reader against pages built byte by byte (#510). The character sets are checked
 * against what FFmpeg's libzvbi decoder printed for the same pages, and the timing against what VLC
 * does: a page shows from the PTS of the payload that brings it.
 */
class TeletextPageReaderTest {

    private val page = 0x888

    private fun pageOf(cues: List<SubtitleCue.Text>): SubtitleCue.Text = cues.single()

    private fun TeletextPageReader.text(vararg units: ByteArray, pts: Long = 1_000_000): String =
        pageOf(read(payload(*units), pts)).plainText

    @Test
    fun boxedTextShowsFromThePayloadThatBringsIt() {
        val cue = pageOf(
            TeletextPageReader(page).read(
                payload(header(page), row(page, 20, *boxed("Hello there")), row(page, 22, *boxed("second line"))),
                1_000_000,
            ),
        )
        assertEquals(1_000_000, cue.startMicros)
        assertEquals(SubtitleCue.OPEN_END, cue.endMicros)
        assertEquals("Hello there\nsecond line", cue.plainText)
        assertEquals(CueAlignment.BottomCenter, cue.layout.alignment)
        assertEquals(listOf(CueStyle()), cue.spans.map { it.style }, "white on the black box is the player's own style")
    }

    @Test
    fun anUnchangedResendMakesNoCue() {
        val reader = TeletextPageReader(page)
        val units = arrayOf(header(page), row(page, 20, *boxed("Hello there")))
        assertEquals(1, reader.read(payload(*units), 1_000_000).size)
        assertEquals(emptyList(), reader.read(payload(*units), 2_000_000))
        assertEquals(emptyList(), reader.read(payload(*units), 3_000_000))
    }

    @Test
    fun aResendSplitAcrossPayloadsDoesNotBlink() {
        val reader = TeletextPageReader(page)
        reader.read(payload(header(page), row(page, 20, *boxed("Hello there"))), 1_000_000)
        // The header erases, and its rows come in the payload after it.
        assertEquals(emptyList(), reader.read(payload(header(page)), 2_000_000))
        assertEquals(emptyList(), reader.read(payload(row(page, 20, *boxed("Hello there"))), 2_040_000))

        assertEquals(emptyList(), reader.read(payload(header(page)), 3_000_000))
        val next = pageOf(reader.read(payload(row(page, 20, *boxed("New words"))), 3_040_000))
        assertEquals("New words", next.plainText)
        assertEquals(3_040_000, next.startMicros)
    }

    @Test
    fun anEraseThatNothingFollowsClearsAtItsOwnTime() {
        val reader = TeletextPageReader(page)
        reader.read(payload(header(page), row(page, 20, *boxed("Hello there"))), 1_000_000)
        assertEquals(emptyList(), reader.read(payload(header(page)), 2_000_000))
        val clear = pageOf(reader.read(payload(stuffing), 2_040_000))
        assertEquals(2_000_000, clear.startMicros)
        assertEquals(SubtitleCue.OPEN_END, clear.endMicros)
        assertEquals(emptyList(), clear.spans)
        assertEquals(emptyList(), reader.read(payload(stuffing), 2_080_000), "a clear page stays clear")
    }

    @Test
    fun anotherPageEndingThePageReleasesTheClearAtTheEraseTime() {
        val reader = TeletextPageReader(page)
        reader.read(payload(header(page, serial = false), row(page, 20, *boxed("Hello there"))), 1_000_000)
        assertEquals(emptyList(), reader.read(payload(header(page, serial = false)), 2_000_000))
        val clear = pageOf(reader.read(payload(header(0x801, serial = false)), 2_040_000))
        assertEquals(2_000_000, clear.startMicros)
        assertEquals("", clear.plainText)
    }

    @Test
    fun endReleasesAHeldClearAndNothingElse() {
        val reader = TeletextPageReader(page)
        assertEquals(emptyList(), reader.end())
        reader.read(payload(header(page), row(page, 20, *boxed("Hello there"))), 1_000_000)
        assertEquals(emptyList(), reader.end())
        reader.read(payload(header(page)), 2_000_000)
        assertEquals(2_000_000, pageOf(reader.end()).startMicros)
        assertEquals(emptyList(), reader.end())
    }

    @Test
    fun resetForgetsWhatWasShown() {
        val reader = TeletextPageReader(page)
        val units = payload(header(page), row(page, 20, *boxed("Hello there")))
        reader.read(units, 1_000_000)
        reader.reset()
        assertEquals("Hello there", pageOf(reader.read(units, 9_000_000)).plainText, "after a seek the page shows again")
        reader.read(payload(header(page)), 10_000_000)
        reader.reset()
        assertEquals(emptyList(), reader.end(), "a seek drops a held clear")
    }

    @Test
    fun aSerialHeaderEndsThePageAndAParallelOneOnlyItsMagazine() {
        val parallel = TeletextPageReader(page).text(
            header(page, serial = false),
            header(0x100, serial = false),
            row(page, 20, *boxed("Kept")),
        )
        assertEquals("Kept", parallel)
        val serial = TeletextPageReader(page).read(
            payload(header(page), header(0x100), row(page, 20, *boxed("Lost"))),
            1_000_000,
        )
        assertEquals(emptyList(), serial, "in serial mode a row after any header belongs to that header's page")
    }

    @Test
    fun rowsOfOtherPagesAreIgnored() {
        val reader = TeletextPageReader(page)
        val cues = reader.read(
            payload(header(0x150), row(0x150, 20, *boxed("Not mine")), header(page), row(page, 20, *boxed("Mine"))),
            1_000_000,
        )
        assertEquals("Mine", pageOf(cues).plainText)
    }

    @Test
    fun withNoPageTheFirstSubtitlePageIsShown() {
        val text = TeletextPageReader().text(
            header(0x100, subtitle = false),
            row(0x100, 20, *boxed("Index")),
            header(0x777),
            row(0x777, 20, *boxed("Subtitles")),
        )
        assertEquals("Subtitles", text)
    }

    @Test
    fun anInhibitedPageShowsNothing() {
        val cues = TeletextPageReader(page).read(payload(header(page, inhibit = true), row(page, 20, *boxed("Hidden"))), 1_000_000)
        assertEquals(emptyList(), cues)
    }

    @Test
    fun onlyTheBoxedTextOfASubtitlePageShows() {
        val text = TeletextPageReader(page).text(
            header(page),
            row(page, 20, 'X'.code, 'X'.code, *boxed("Shown"), 'Y'.code),
        )
        assertEquals("Shown", text)
        val plain = TeletextPageReader(0x100).text(header(0x100, subtitle = false), row(0x100, 20, *"Plain page".codes()))
        assertEquals("Plain page", plain, "a page that is not a subtitle page shows every character")
    }

    @Test
    fun theTopHalfAndTheBottomHalfAreSeparateCues() {
        val cues = TeletextPageReader(page).read(
            payload(header(page), row(page, 2, *boxed("Top words")), row(page, 20, *boxed("Bottom words"))),
            1_000_000,
        )
        assertEquals(listOf("Top words", "Bottom words"), cues.map { it.plainText })
        assertEquals(listOf(CueAlignment.TopCenter, CueAlignment.BottomCenter), cues.map { it.layout.alignment })
        assertEquals(listOf(1_000_000L, 1_000_000L), cues.map { it.startMicros })
    }

    @Test
    fun coloursAndBackgroundsBecomeSpanStyles() {
        val red = 0xFFFF0000.toInt()
        val green = 0xFF00FF00.toInt()
        val blue = 0xFF0000FF.toInt()
        val coloured = pageOf(
            TeletextPageReader(page).read(
                payload(header(page), row(page, 20, START_BOX, START_BOX, 0x01, *"Red".codes(), 0x02, *"Go".codes(), END_BOX, END_BOX)),
                1_000_000,
            ),
        )
        assertEquals(
            listOf(StyledSpan("Red ", CueStyle(primaryColor = red)), StyledSpan("Go", CueStyle(primaryColor = green))),
            coloured.spans,
        )
        // Blue, new background, then white: white text on a blue box.
        val boxedInBlue = pageOf(
            TeletextPageReader(page).read(
                payload(header(page), row(page, 20, START_BOX, START_BOX, 0x04, 0x1D, 0x07, *"Hi".codes(), 0x1C, *"there".codes(), END_BOX, END_BOX)),
                1_000_000,
            ),
        )
        assertEquals(
            listOf(StyledSpan("Hi", CueStyle(backgroundColor = blue)), StyledSpan(" there", CueStyle())),
            boxedInBlue.spans,
            "a box ends where the page ends it, and the space after it is not boxed",
        )
    }

    @Test
    fun mosaicGraphicsShowAsSpaces() {
        val text = TeletextPageReader(page).text(
            header(page),
            row(page, 20, START_BOX, START_BOX, *"A".codes(), 0x11, 0x7F, 0x07, *"B".codes(), END_BOX, END_BOX),
        )
        assertEquals("A   B", text)
    }

    @Test
    fun doubleHeightKeepsItsProportionAndCoversTheRowBelow() {
        val cue = pageOf(
            TeletextPageReader(page).read(
                payload(
                    header(page),
                    row(page, 18, 0x0D, *boxed("Big")),
                    row(page, 19, *boxed("covered")),
                    row(page, 20, *boxed("small")),
                ),
                1_000_000,
            ),
        )
        assertEquals("Big\nsmall", cue.plainText)
        assertEquals(listOf(1f, 0.5f), cue.spans.map { it.style.relativeSize })

        val alone = pageOf(TeletextPageReader(page).read(payload(header(page), row(page, 18, 0x0D, *boxed("Big"))), 1_000_000))
        assertEquals(listOf(1f), alone.spans.map { it.style.relativeSize }, "a page of one height draws at the player's size")
    }

    @Test
    fun theHeaderNationalOptionPicksTheLetters() {
        assertEquals("Grüße", TeletextPageReader(page).text(header(page, option = 1), row(page, 20, *boxed("Gr}~e"))))
        assertEquals("Gr¾÷e", TeletextPageReader(page).text(header(page), row(page, 20, *boxed("Gr}~e"))))
    }

    /** Each code's characters 0x21 to 0x7F as libzvbi printed them, corrected where the reader's table says why. */
    private val libzvbiSets = listOf(
        "!\"£\$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ←½→↑#\u2014abcdefghijklmnopqrstuvwxyz¼‖¾÷■" to listOf(0x00, 0x10, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30, 0x31, 0x32, 0x33, 0x34, 0x35, 0x38, 0x39, 0x3A, 0x3B, 0x3C, 0x3D, 0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x45, 0x46, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0x51, 0x52, 0x53, 0x54, 0x56),
        "!\"#\$%&'()*+,-./0123456789:;<=>?§ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÜ^_°abcdefghijklmnopqrstuvwxyzäöüß■" to listOf(0x01, 0x09, 0x11, 0x21),
        "!\"#¤%&'()*+,-./0123456789:;<=>?ÉABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÅÜ_éabcdefghijklmnopqrstuvwxyzäöåü■" to listOf(0x02, 0x0A, 0x12),
        "!\"£\$%&'()*+,-./0123456789:;<=>?éABCDEFGHIJKLMNOPQRSTUVWXYZ°ç→↑#ùabcdefghijklmnopqrstuvwxyzàòèì■" to listOf(0x03, 0x0B, 0x13),
        "!\"éï%&'()*+,-./0123456789:;<=>?àABCDEFGHIJKLMNOPQRSTUVWXYZëêùî#èabcdefghijklmnopqrstuvwxyzâôûç■" to listOf(0x04, 0x0C, 0x14, 0x44),
        "!\"ç\$%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZáéíóú¿abcdefghijklmnopqrstuvwxyzüñèà■" to listOf(0x05, 0x15),
        "!\"#ů%&'()*+,-./0123456789:;<=>?čABCDEFGHIJKLMNOPQRSTUVWXYZťžýířéabcdefghijklmnopqrstuvwxyzáěúš■" to listOf(0x06, 0x0E, 0x26),
        "!\"#¤%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{¦}~■" to listOf(0x07, 0x0D, 0x0F, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1E),
        "!\"#ń%&'()*+,-./0123456789:;<=>?ąABCDEFGHIJKLMNOPQRSTUVWXYZŻŚŁćóęabcdefghijklmnopqrstuvwxyzżśłź■" to listOf(0x08),
        "!\"₺ğ%&'()*+,-./0123456789:;<=>?İABCDEFGHIJKLMNOPQRSTUVWXYZŞÖÇÜĞıabcdefghijklmnopqrstuvwxyzşöçü■" to listOf(0x16, 0x36),
        "!\"#Ë%&'()*+,-./0123456789:;<=>?ČABCDEFGHIJKLMNOPQRSTUVWXYZĆŽĐŠëčabcdefghijklmnopqrstuvwxyzćžđš■" to listOf(0x1D),
        "!\"#¤%&'()*+,-./0123456789:;<=>?ŢABCDEFGHIJKLMNOPQRSTUVWXYZÂŞĂÎıţabcdefghijklmnopqrstuvwxyzâşăî■" to listOf(0x1F),
        "!\"#\$%&'()*+,-./0123456789:;<=>?ЧАБЦДЕФГХИЈКЛМНОПЌРСТУВЃЉЊЗЋЖЂШЏчабцдефгхијклмнопќрстувѓљњзћжђш■" to listOf(0x20),
        "!\"#õ%&'()*+,-./0123456789:;<=>?ŠABCDEFGHIJKLMNOPQRSTUVWXYZÄÖŽÜÕšabcdefghijklmnopqrstuvwxyzäöžü■" to listOf(0x22),
        "!\"#\$%&'()*+,-./0123456789:;<=>?ŠABCDEFGHIJKLMNOPQRSTUVWXYZėęŽčūšabcdefghijklmnopqrstuvwxyząųžį■" to listOf(0x23),
        "!\"#\$%ы'()*+,-./0123456789:;<=>?ЮАБЦДЕФГХИЍКЛМНОПЯРСТУЖВЬЪЗШЭЩЧЫюабцдефгхиѝклмнопярстужвьъзшэщч■" to listOf(0x24),
        "!\"#\$%ï'()*+,-./0123456789:;<=>?ЮАБЦДЕФГХИЍКЛМНОПЯРСТУЖВЬІЗШЄЩЧЇюабцдефгхиѝклмнопярстужвьізшєщч■" to listOf(0x25),
        "!\"#\$%&'()*+,-./0123456789:;«=»?ΐΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡʹΣΤΥΦΧΨΩΪΫάέήίΰαβγδεζηθικλμνξοπρςστυφχψωϊϋόύώ■" to listOf(0x37),
        "!\"#\$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ←½→↑#אבגדהוזחטיךכלםמןנסעףפץצקרשת₪‖¾÷■" to listOf(0x55),
    )

    @Test
    fun everyCharacterSetMatchesLibzvbi() {
        val chunks = (0x21..0x7F).chunked(16)
        var checked = 0
        for ((expected, codes) in libzvbiSets) {
            for (code in codes) {
                // The page names the set and its header the national option, as the libzvbi page did.
                val units = listOf(header(page, option = code and 7), pageSets(page, code)) +
                    chunks.mapIndexed { index, chunk -> row(page, 12 + 2 * index, START_BOX, START_BOX, *chunk.toIntArray(), END_BOX, END_BOX) }
                val text = pageOf(TeletextPageReader(page).read(payload(units), 1_000_000)).plainText
                assertEquals(expected, text.replace("\n", ""), "designation 0x${code.toString(16)}")
                checked++
            }
        }
        assertEquals(0x58 - 2, checked, "every code but the two Arabic ones")
    }

    @Test
    fun arabicReadsAsEnglish() {
        for (code in listOf(0x47, 0x57)) {
            val text = TeletextPageReader(page).text(header(page, option = code and 7), pageSets(page, code), row(page, 20, *boxed("[x]")))
            assertEquals("←x→", text)
        }
    }

    @Test
    fun theStreamLanguageNamesTheRegionWhenTheBroadcasterDoesNot() {
        val letters = arrayOf(header(page), row(page, 20, *boxed("[")))
        assertEquals("←", TeletextPageReader(page).text(*letters))
        assertEquals("Ż", TeletextPageReader(page, "pol").text(*letters))
        assertEquals("Ż", TeletextPageReader(page, "pl").text(*letters))
        assertEquals("Ş", TeletextPageReader(page, "tur").text(header(page, option = 6), row(page, 20, *boxed("["))))
        assertEquals("Я", TeletextPageReader(page, "rus").text(header(page, option = 4), row(page, 20, *boxed("Q"))))
        assertEquals("ט", TeletextPageReader(page, "heb").text(header(page, option = 5), row(page, 20, *boxed("h"))))
        // A designation the broadcaster sends beats the guess.
        assertEquals("←", TeletextPageReader(page, "pol").text(header(page), pageSets(page, 0x00), row(page, 20, *boxed("["))))
    }

    @Test
    fun aMagazineDesignationAppliesUnlessThePageNamesItsOwn() {
        assertEquals("Ż", TeletextPageReader(page).text(magazineSets(8, 0x08), header(page), row(page, 20, *boxed("["))))
        assertEquals(
            "Ä",
            TeletextPageReader(page).text(magazineSets(8, 0x08), header(page, option = 1), pageSets(page, 0x01), row(page, 20, *boxed("["))),
        )
        assertEquals(
            "←",
            TeletextPageReader(page).text(magazineSets(1, 0x08), header(page), row(page, 20, *boxed("["))),
            "another magazine's designation is not this page's",
        )
    }

    @Test
    fun escapeSwitchesToTheSecondSet() {
        val text = TeletextPageReader(page).text(
            header(page),
            pageSets(page, 0x00, second = 0x20),
            row(page, 20, START_BOX, START_BOX, *"A".codes(), 0x1B, *"A".codes(), 0x1B, *"A".codes(), END_BOX, END_BOX),
        )
        assertEquals("A А A", text)
    }

    @Test
    fun enhancementPacketsPlaceAccentsAndSecondSetCharacters() {
        // Columns 2 to 6 of row 20 hold "aeq x", which the X/26 packet then rewrites in place.
        val triplets = listOf(
            triplet(40 + 20, 0x04, 0),
            triplet(2, 0x12, 'a'.code),
            triplet(3, 0x18, 'e'.code),
            // Unicode has no q with a caron, so the mark combines.
            triplet(4, 0x1F, 'q'.code),
            triplet(5, 0x0F, 0x21),
            triplet(6, 0x10, 0x2A),
        )
        val text = TeletextPageReader(page).text(header(page), row(page, 20, *boxed("aeq x")), *enhancements(page, triplets).toTypedArray())
        assertEquals("áëq̌¡@", text)
    }

    @Test
    fun enhancementPacketsStopAtTheirTermination() {
        val triplets = listOf(
            triplet(40 + 20, 0x04, 0),
            triplet(2, 0x12, 'a'.code),
            triplet(63, 0x1F, 0x7F),
            triplet(3, 0x12, 'e'.code),
        )
        val text = TeletextPageReader(page).text(header(page), row(page, 20, *boxed("ae")), *enhancements(page, triplets).toTypedArray())
        assertEquals("áe", text)
    }

    @Test
    fun aResentRowHealsACharacterThatFailedItsParity() {
        val reader = TeletextPageReader(page)
        reader.read(payload(header(page), row(page, 20, *boxed("Hello"))), 1_000_000)
        val damaged = row(page, 20, *boxed("Hello")).also { it.damageColumn(3) }
        assertEquals(emptyList(), reader.read(payload(header(page, erase = false), damaged), 2_000_000), "the earlier copy fills the gap")

        val fresh = TeletextPageReader(page)
        assertEquals("H llo", fresh.text(header(page), row(page, 20, *boxed("Hello")).also { it.damageColumn(3) }))
    }

    @Test
    fun anAddressWithOneBadBitIsCorrectedAndOneWithTwoIsDropped() {
        val oneBit = row(page, 20, *boxed("Corrected")).also { it[ADDRESS] = (it[ADDRESS].toInt() xor 0x10).toByte() }
        assertEquals("Corrected", TeletextPageReader(page).text(header(page), oneBit))

        val twoBits = row(page, 20, *boxed("Dropped")).also { it[ADDRESS] = (it[ADDRESS].toInt() xor 0x11).toByte() }
        assertEquals(emptyList(), TeletextPageReader(page).read(payload(header(page), twoBits), 1_000_000))
    }

    @Test
    fun hammingCodesCorrectOneErrorAndRefuseTwo() {
        for (value in 0..15) {
            val word = TeletextUnits.hamming84(value)
            assertEquals(value, TeletextCoding.hamming84(word))
            for (bit in 0..7) {
                assertEquals(value, TeletextCoding.hamming84(word xor (1 shl bit)))
                for (other in bit + 1..7) assertEquals(-1, TeletextCoding.hamming84(word xor (1 shl bit) xor (1 shl other)))
            }
        }
        for (value in listOf(0, 1, 0x2AAAA, 0x15555, 0x3FFFF, triplet(60, 0x04, 0), triplet(5, 0x1F, 'q'.code))) {
            val bytes = TeletextUnits.hamming2418(value)
            val word = bytes[0] or (bytes[1] shl 8) or (bytes[2] shl 16)
            fun decode(w: Int) = TeletextCoding.hamming2418(w and 0xFF, w shr 8 and 0xFF, w shr 16 and 0xFF)
            assertEquals(value, decode(word))
            for (bit in 0..23) {
                assertEquals(value, decode(word xor (1 shl bit)), "bit $bit of 0x${value.toString(16)}")
                for (other in bit + 1..23) assertEquals(-1, decode(word xor (1 shl bit) xor (1 shl other)))
            }
        }
    }

    @Test
    fun payloadsThatAreNotTeletextAreIgnored() {
        val units = payload(header(page), row(page, 20, *boxed("Hello")))
        assertEquals(emptyList(), TeletextPageReader(page).read(byteArrayOf(0x20) + units.copyOfRange(1, units.size), 1_000_000))
        assertEquals(emptyList(), TeletextPageReader(page).read(ByteArray(0), 1_000_000))
        assertEquals(
            "Hello",
            pageOf(TeletextPageReader(page).read(byteArrayOf(0x99.toByte()) + units.copyOfRange(1, units.size), 1_000_000)).plainText,
            "EN 301 775 carries teletext under its own identifiers",
        )
        // A unit cut short by the end of the payload is not read.
        assertEquals(emptyList(), TeletextPageReader(page).read(units.copyOfRange(0, units.size - 1), 1_000_000))
    }

    @Test
    fun aPageOutsideTheTeletextRangeIsRefused() {
        assertFailsWith<IllegalArgumentException> { TeletextPageReader(0x900) }
        assertFailsWith<IllegalArgumentException> { TeletextPageReader(0x88) }
        assertTrue(TeletextPageReader(0x100).read(payload(stuffing), 0).isEmpty())
    }

    private fun String.codes(): IntArray = map { it.code }.toIntArray()

    /** Breaks the parity of the character in [column] of a unit that [row] built. */
    private fun ByteArray.damageColumn(column: Int) {
        this[DATA + column] = (this[DATA + column].toInt() xor 0x01).toByte()
    }

    private companion object {
        /** Where a unit's first address byte and its first character sit, after the unit id and length. */
        const val ADDRESS = 2 + 2
        const val DATA = 2 + 4
    }
}
