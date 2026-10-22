package io.github.yuroyami.kiteplayer.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The text half of a list of streams: recognising a PLS file, and reading it or a plain M3U list (#450). */
class StreamListsTest {

    @Test
    fun aPlsFileIsRecognisedByHintTypeOrExtension() {
        assertTrue(looksLikePls("pls", null, null))
        assertTrue(looksLikePls(null, "audio/x-scpls", "https://radio.test/listen"))
        assertTrue(looksLikePls(null, "Audio/X-SCPLS; charset=ISO-8859-1", null))
        assertTrue(looksLikePls(null, "application/pls+xml", null))
        assertTrue(looksLikePls(null, null, "https://radio.test/station.PLS?sid=1"))
        assertFalse(looksLikePls(null, "audio/mpeg", "https://radio.test/listen"))
        assertFalse(looksLikePls(null, null, "https://radio.test/pls/stream.mp3"))
        // A format hint names the format, so a PLS-looking address does not override it.
        assertFalse(looksLikePls("mp3", null, "https://radio.test/station.pls"))
    }

    @Test
    fun aPlsFileIsRecognisedByItsFirstLine() {
        assertTrue(startsLikePls("[playlist]\nFile1=http://radio.test/\n".encodeToByteArray()))
        assertTrue(startsLikePls("﻿\r\n[Playlist]\r\nNumberOfEntries=1".encodeToByteArray()))
        // Only the first bytes a reader gave count, however large the buffer.
        assertFalse(startsLikePls("[playlist]".encodeToByteArray().copyOf(32), length = 9))
        assertFalse(startsLikePls("#EXTM3U\nhttp://radio.test/\n".encodeToByteArray()))
        assertFalse(startsLikePls("[ar:Artist]\n[00:01.00]A lyric line\n".encodeToByteArray()))
        assertFalse(startsLikePls(ByteArray(0)))
    }

    @Test
    fun aPlsFileListsItsEntriesInTheOrderItNumbersThem() {
        val text = """
            [playlist]
            NumberOfEntries=3
            File2=http://backup.radio.test:8000/;
            Title2=Backup
            file1 = http://radio.test:8000/live
            TITLE1=Radio One (128k)
            File3=low.mp3
            Length3=-1
            Version=2
        """.trimIndent()
        val entries = parsePls(text, "https://radio.test/lists/station.pls")
        assertEquals(
            listOf("http://radio.test:8000/live", "http://backup.radio.test:8000/;", "https://radio.test/lists/low.mp3"),
            entries.map { it.address },
        )
        assertEquals(listOf("Radio One (128k)", "Backup", null), entries.map { it.title })
    }

    @Test
    fun aPlsFileWithNoEntryListsNothing() {
        assertEquals(0, parsePls("[playlist]\nNumberOfEntries=0\nVersion=2\n", "https://radio.test/a.pls").size)
        assertEquals(0, parsePls("[playlist]\nFile1=\n", "https://radio.test/a.pls").size)
    }

    @Test
    fun aPlainM3uListNamesItsStreamsAndTheirTitles() {
        val text = "﻿#EXTM3U\r\n#EXTINF:-1,Radio One\r\nhttp://radio.test:8000/live\r\n\r\n# a comment\r\nbackup\r\n"
        val entries = assertNotNull(plainStreamList(text, "https://radio.test/lists/station.m3u"))
        assertEquals(listOf("http://radio.test:8000/live", "https://radio.test/lists/backup"), entries.map { it.address })
        assertEquals(listOf("Radio One", null), entries.map { it.title })
    }

    @Test
    fun anIptvTitleFollowsTheFirstCommaOutsideQuotes() {
        val text = "#EXTM3U\n#EXTINF:-1 tvg-id=\"one\" tvg-name=\"News, Weather\" group-title=\"A, B\",News Channel\nhttp://tv.test/news\n"
        val entries = assertNotNull(plainStreamList(text, "http://tv.test/list.m3u"))
        assertEquals("News Channel", entries.single().title)
    }

    @Test
    fun aListWithAnyHlsTagIsNotAPlainList() {
        assertNull(plainStreamList("#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXTINF:2.0,\nseg-0.ts\n", "https://cdn.test/a.m3u8"))
        assertNull(plainStreamList("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8\n", "https://cdn.test/a.m3u8"))
    }

    @Test
    fun markupOrAnEmptyListIsNotAPlainList() {
        assertNull(plainStreamList("<!DOCTYPE html>\n<html><body>Not found</body></html>\n", "https://radio.test/a.m3u"))
        assertNull(plainStreamList("#EXTM3U\n#EXTINF:-1,Nothing after it\n", "https://radio.test/a.m3u"))
        assertNull(plainStreamList("", "https://radio.test/a.m3u"))
    }
}
