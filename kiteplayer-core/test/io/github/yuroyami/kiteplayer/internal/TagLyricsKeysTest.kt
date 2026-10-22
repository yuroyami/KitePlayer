package io.github.yuroyami.kiteplayer.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The tags FFmpeg puts lyrics under, and the language an ID3 frame names (#443). */
class TagLyricsKeysTest {

    @Test
    fun everyTagThatHoldsLyricsIsRead() {
        assertEquals("words", tagLyrics(mapOf("title" to "t", "lyrics" to "words"))?.text)
        assertEquals("words", tagLyrics(mapOf("LYRICS" to "words"))?.text)
        assertEquals("words", tagLyrics(mapOf("UNSYNCEDLYRICS" to "words"))?.text)
        assertEquals("words", tagLyrics(mapOf("©lyr" to "words"))?.text)
        val id3 = tagLyrics(mapOf("lyrics-eng" to "words"))
        assertEquals("words", id3?.text)
        assertEquals("eng", id3?.language)
        assertEquals("jpn", tagLyrics(mapOf("lyrics-Album version-jpn" to "words"))?.language)
        assertNull(tagLyrics(mapOf("lyrics-XXX" to "words"))?.language, "XXX names no language")
        assertNull(tagLyrics(mapOf("lyrics" to "words"))?.language)
    }

    @Test
    fun noLyricsTagOrAnEmptyOneIsNone() {
        assertNull(tagLyrics(mapOf("title" to "A song", "artist" to "Somebody")))
        assertNull(tagLyrics(mapOf("lyrics" to "  \n")))
        assertNull(tagLyrics(mapOf("lyricist" to "Somebody")))
    }

    @Test
    fun onlyStampedLinesReadAsLrc() {
        assertTrue(looksLikeLrc("[ar:Somebody]\n[00:01.00]Line"))
        assertTrue(looksLikeLrc("[00:01:50]Line"))
        assertFalse(looksLikeLrc("Plain words\n[00:01.00] later"))
        assertFalse(looksLikeLrc("[Chorus]\nLa la la"))
    }
}
