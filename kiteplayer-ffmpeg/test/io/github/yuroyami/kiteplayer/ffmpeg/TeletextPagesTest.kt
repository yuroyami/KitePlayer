package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.TrackKind
import io.github.yuroyami.kiteplayer.spi.PlayerStreamInfo
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A teletext stream lists one track for each subtitle page its descriptor names (#510), from the
 * extradata and comma list FFmpeg's transport stream reader makes of that descriptor.
 */
class TeletextPagesTest {

    /** A teletext stream at [index] whose descriptor lists [entries], each its language, type and page. */
    private fun stream(index: Int, vararg entries: Triple<String, Int, Int>, isDefault: Boolean = false) = PlayerStreamInfo(
        index = index,
        kind = TrackKind.Subtitle,
        codec = TELETEXT,
        language = entries.joinToString(",") { it.first }.ifEmpty { null },
        isDefault = isDefault,
        codecExtradata = entries.flatMap { (_, type, page) -> listOf((type shl 3) or (page shr 8 and 7), page and 0xFF) }
            .map { it.toByte() }.toByteArray().takeIf { it.isNotEmpty() },
    )

    @Test
    fun eachSubtitlePageIsOneTrackWithItsOwnLanguageAndPage() {
        val pages = teletextPages(stream(3, Triple("eng", 2, 0x888), Triple("fre", 2, 0x150)))
        assertEquals(listOf(3, 0x1000000 or (3 shl 12) or 0x150), pages.map { it.index })
        assertEquals(listOf("eng", "fre"), pages.map { it.language })
        assertEquals(listOf(0x888, 0x150), pages.map { teletextPageOf(it) })
        assertContentEquals(byteArrayOf(0x10, 0x88.toByte()), pages[0].codecExtradata)
    }

    @Test
    fun aHearingImpairedPageCarriesTheMark() {
        val pages = teletextPages(stream(1, Triple("deu", 2, 0x150), Triple("deu", 5, 0x149)))
        assertEquals(listOf(false, true), pages.map { it.isAccessibility })
    }

    @Test
    fun onlyTheFirstPageKeepsTheStreamsDefaultMark() {
        val pages = teletextPages(stream(1, Triple("eng", 2, 0x888), Triple("fre", 2, 0x889), isDefault = true))
        assertEquals(listOf(true, false), pages.map { it.isDefault })
    }

    @Test
    fun pagesThatAreNotSubtitlesAreNoTrack() {
        // The index page, type 1, and a programme schedule, type 4, are full-screen teletext.
        assertTrue(teletextPages(stream(2, Triple("eng", 1, 0x100), Triple("eng", 4, 0x300))).isEmpty())
        val mixed = teletextPages(stream(2, Triple("eng", 1, 0x100), Triple("pol", 2, 0x777)))
        assertEquals(listOf(2), mixed.map { it.index }, "the first subtitle page keeps the stream's index")
        assertEquals(listOf("pol"), mixed.map { it.language })
    }

    @Test
    fun aPageListedTwiceIsOneTrack() {
        val pages = teletextPages(stream(0, Triple("eng", 2, 0x888), Triple("eng", 5, 0x888)))
        assertEquals(listOf(0), pages.map { it.index })
    }

    @Test
    fun aStreamWithNoDescriptorIsOneTrackThatFindsItsPage() {
        val bare = stream(4)
        assertEquals(listOf(bare), teletextPages(bare))
        assertNull(teletextPageOf(bare))
    }
}
