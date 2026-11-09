package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals

/** [Playlists.resolve], the address of one entry beside its playlist, as the reader of every list resolves it. */
class PlaylistResolveTest {

    @Test
    fun anEntryResolvesBesideItsList() {
        assertEquals("https://cdn.test/hls/ts-1.m3u8", Playlists.resolve("https://cdn.test/hls/master.m3u8?t=1", "ts-1.m3u8"))
        assertEquals("https://cdn.test/a/b.ts", Playlists.resolve("https://cdn.test/hls/x/master.m3u8", "../../a/b.ts"))
        assertEquals("https://backup.test/hls/ts-1.m3u8", Playlists.resolve("https://cdn.test/hls/master.m3u8", "https://backup.test/hls/ts-1.m3u8"))
        assertEquals("/music/song.mp3", Playlists.resolve("/music/list.m3u", "song.mp3"))
    }

    /** RFC 3986, 5.4: the dot-segment examples, which the index walk must answer as before (#854). */
    @Test
    fun dotSegmentsResolveAsRfc3986Says() {
        val base = "http://a/b/c/d;p?q"
        val expected = mapOf(
            "g" to "http://a/b/c/g", "./g" to "http://a/b/c/g", "g/" to "http://a/b/c/g/", "/g" to "http://a/g",
            "." to "http://a/b/c/", "./" to "http://a/b/c/", ".." to "http://a/b/", "../" to "http://a/b/",
            "../g" to "http://a/b/g", "../.." to "http://a/", "../../" to "http://a/", "../../g" to "http://a/g",
            "../../../g" to "http://a/g", "../../../../g" to "http://a/g", "/./g" to "http://a/g", "/../g" to "http://a/g",
            "g." to "http://a/b/c/g.", ".g" to "http://a/b/c/.g", "g.." to "http://a/b/c/g..", "..g" to "http://a/b/c/..g",
            "./../g" to "http://a/b/g", "./g/." to "http://a/b/c/g/", "g/./h" to "http://a/b/c/g/h", "g/../h" to "http://a/b/c/h",
            "g;x=1/./y" to "http://a/b/c/g;x=1/y", "g;x=1/../y" to "http://a/b/c/y",
        )
        for ((reference, resolved) in expected) assertEquals(resolved, Playlists.resolve(base, reference), reference)
    }

    /**
     * A segment line of two megabytes resolves at once. The old walk copied the rest of the path at
     * every segment, the square of its length, which is minutes at this size (#854).
     */
    @Test
    fun aVeryLongEntryResolvesInBoundedTime() {
        val line = "a/".repeat(1_000_000) + "x.ts"
        val took = kotlin.time.measureTime {
            assertEquals("https://cdn.test/hls/$line", Playlists.resolve("https://cdn.test/hls/index.m3u8", line))
        }
        kotlin.test.assertTrue(took.inWholeSeconds < 10, "resolving took $took")
    }
}
