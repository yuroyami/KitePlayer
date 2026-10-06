package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaClip
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Playlists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/** Playlist files as queue items (#490): M3U, PLS and XSPF, and the addresses they name. */
class PlaylistsTest {

    private val songs = listOf("One", "Two", "Three", "Four", "Five")

    private fun expected(base: String) = songs.mapIndexed { index, title -> "$base/${index + 1} $title.mp3" to title }

    private fun read(items: List<MediaItem>?) = items.orEmpty().map { it.uri to it.title }

    @Test
    fun anM3uListWithRelativeEntriesTitlesAndCrlfIsAQueue() {
        val m3u = "#EXTM3U\r\n" + songs.withIndex().joinToString("") { (index, title) ->
            "#EXTINF:180,$title\r\n${index + 1} $title.mp3\r\n"
        }
        assertEquals(expected("/music/album"), read(Playlists.parse(m3u, "/music/album/list.m3u")))
    }

    @Test
    fun thePlsAndXspfFormsOfTheSameListGiveTheSameQueue() {
        val pls = "[playlist]\n" + songs.withIndex().joinToString("") { (index, title) ->
            "File${index + 1}=${index + 1} $title.mp3\nTitle${index + 1}=$title\n"
        } + "NumberOfEntries=5\nVersion=2\n"
        assertEquals(expected("/music/album"), read(Playlists.parse(pls, "/music/album/list.pls")))
        val xspf = """<?xml version="1.0" encoding="UTF-8"?>
            <playlist version="1" xmlns="http://xspf.org/ns/0/"><trackList>
            ${songs.withIndex().joinToString("") { (index, title) -> "<track><location>${index + 1} $title.mp3</location><title>$title</title></track>" }}
            </trackList></playlist>"""
        assertEquals(expected("/music/album"), read(Playlists.parse(xspf, "/music/album/list.xspf")))
    }

    @Test
    fun anHlsPlaylistIsNoQueue() {
        assertNull(Playlists.parse("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg0.ts\n", "https://cdn.test/live.m3u8"))
        assertNull(Playlists.parse("<html><body>not found</body></html>", "https://cdn.test/list.m3u"))
    }

    @Test
    fun aListWrittenOnWindowsResolvesItsBackslashes() {
        val m3u = "Disc 1\\01 Intro.mp3\r\n..\\Other\\02 Song.flac\r\n"
        assertEquals(
            listOf("/music/Album/Disc 1/01 Intro.mp3", "/music/Other/02 Song.flac"),
            read(Playlists.parse(m3u, "/music/Album/list.m3u")).map { it.first },
        )
        assertEquals(
            listOf("C:\\Music\\Album\\Disc 1\\01 Intro.mp3", "C:\\Music\\Other\\02 Song.flac"),
            read(Playlists.parse(m3u, "C:\\Music\\Album\\list.m3u")).map { it.first },
        )
    }

    @Test
    fun fileAddressesBecomePathsAndServerAddressesResolveBesideTheList() {
        val m3u = "file:///home/me/Music/A%20Song.mp3\nfile:///C:/Music/B.mp3\nhttps://radio.test/live\n"
        assertEquals(
            listOf("/home/me/Music/A Song.mp3", "C:/Music/B.mp3", "https://radio.test/live"),
            read(Playlists.parse(m3u, "/anywhere/list.m3u")).map { it.first },
        )
        assertEquals(
            listOf("https://host.test/lists/a.mp3", "https://host.test/b.mp3"),
            read(Playlists.parse("a.mp3\n../b.mp3\n", "https://host.test/lists/x.m3u?token=1")).map { it.first },
        )
    }

    @Test
    fun iptvOptionsBecomeTheItemsHeaders() {
        val m3u = "#EXTM3U\n#EXTINF:-1 tvg-name=\"A, B\",Channel One\n#EXTVLCOPT:http-referrer=https://site.test/\n" +
            "#EXTVLCOPT:http-user-agent=Box/1.0\nhttps://tv.test/one.ts\nhttps://tv.test/two.ts\n"
        val items = Playlists.parse(m3u, "https://tv.test/list.m3u").orEmpty()
        assertEquals("Channel One", items[0].title)
        assertEquals(mapOf("Referer" to "https://site.test/", "User-Agent" to "Box/1.0"), items[0].headers)
        assertEquals(emptyMap(), items[1].headers, "the options of one entry carried to the next")
    }

    @Test
    fun anXspfTracksNamesAndEntitiesAreRead() {
        val xspf = """<playlist><trackList><track><title>Rock &amp; Roll</title><creator>Somebody</creator>
            <album>&#x41;lbum</album><location>file:///music/r%26r.ogg</location></track></trackList></playlist>"""
        val item = Playlists.parse(xspf, "/lists/x.xspf").orEmpty().single()
        assertEquals(listOf("/music/r&r.ogg", "Rock & Roll", "Somebody", "Album"), listOf(item.uri, item.title, item.artist, item.album))
    }

    /** An album ripped to one file with its cue sheet plays as its tracks (#456). */
    @Test
    fun aCueSheetsTracksAreClipsOfItsFile() {
        val cue = """
            REM GENRE Rock
            PERFORMER "The Band"
            TITLE "The Album"
            FILE "The Album.flac" WAVE
              TRACK 01 AUDIO
                TITLE "Opening"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Second Song"
                PERFORMER "A Guest"
                INDEX 00 04:10:00
                INDEX 01 04:12:37
              TRACK 03 AUDIO
                TITLE "Closer"
                INDEX 01 08:03:00
        """.trimIndent()
        val items = Playlists.parse(cue, "/music/The Band/album.cue").orEmpty()
        assertEquals(List(3) { "/music/The Band/The Album.flac" }, items.map { it.uri })
        assertEquals(listOf("Opening", "Second Song", "Closer"), items.map { it.title })
        assertEquals(listOf("The Band", "A Guest", "The Band"), items.map { it.artist })
        assertEquals(List(3) { "The Album" }, items.map { it.album })
        val clips = items.map { it.clip!! }
        // The pregap of track 2 plays at the end of track 1.
        assertEquals(MediaClip(0.seconds, 250.seconds), clips[0])
        assertEquals((4 * 60 + 12).seconds + (37 * 1_000_000 / 75).microseconds, clips[1].start)
        assertEquals(483.seconds, clips[1].end)
        assertEquals(MediaClip(483.seconds, null), clips[2])
    }

    @Test
    fun aCueSheetOfSeveralFilesEndsEachFilesLastTrackAtItsEnd() {
        val cue = "FILE disc1.wav WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 03:00:00\n" +
            "FILE \"disc 2.wav\" WAVE\nTRACK 03 AUDIO\nINDEX 01 00:00:00\nTRACK 04 MODE1/2352\nINDEX 01 05:00:00\n"
        val items = Playlists.parse(cue, "https://host.test/rips/album.cue").orEmpty()
        assertEquals(
            listOf("https://host.test/rips/disc1.wav", "https://host.test/rips/disc1.wav", "https://host.test/rips/disc 2.wav"),
            items.map { it.uri },
            "a data track was taken for audio",
        )
        assertEquals(listOf(MediaClip(0.seconds, 180.seconds), MediaClip(180.seconds, null), MediaClip(0.seconds, null)), items.map { it.clip })
    }

    @Test
    fun referencesResolveAsRfc3986Says() {
        val base = "http://a/b/c/d;p?q"
        val cases = mapOf(
            "g:h" to "g:h",
            "g" to "http://a/b/c/g",
            "./g" to "http://a/b/c/g",
            "g/" to "http://a/b/c/g/",
            "/g" to "http://a/g",
            "//g" to "http://g",
            "?y" to "http://a/b/c/d;p?y",
            "g?y" to "http://a/b/c/g?y",
            "#s" to "http://a/b/c/d;p?q#s",
            "g#s" to "http://a/b/c/g#s",
            "g?y#s" to "http://a/b/c/g?y#s",
            ";x" to "http://a/b/c/;x",
            "g;x" to "http://a/b/c/g;x",
            "" to "http://a/b/c/d;p?q",
            "." to "http://a/b/c/",
            "./" to "http://a/b/c/",
            ".." to "http://a/b/",
            "../" to "http://a/b/",
            "../g" to "http://a/b/g",
            "../.." to "http://a/",
            "../../" to "http://a/",
            "../../g" to "http://a/g",
            "../../../g" to "http://a/g",
            "../../../../g" to "http://a/g",
            "/./g" to "http://a/g",
            "/../g" to "http://a/g",
            "g." to "http://a/b/c/g.",
            ".g" to "http://a/b/c/.g",
            "g.." to "http://a/b/c/g..",
            "..g" to "http://a/b/c/..g",
            "./../g" to "http://a/b/g",
            "./g/." to "http://a/b/c/g/",
            "g/./h" to "http://a/b/c/g/h",
            "g/../h" to "http://a/b/c/h",
            "g;x=1/./y" to "http://a/b/c/g;x=1/y",
            "g;x=1/../y" to "http://a/b/c/y",
        )
        for ((reference, expected) in cases) {
            assertEquals(expected, resolveUriReference(base, reference), "for \"$reference\"")
        }
    }
}
