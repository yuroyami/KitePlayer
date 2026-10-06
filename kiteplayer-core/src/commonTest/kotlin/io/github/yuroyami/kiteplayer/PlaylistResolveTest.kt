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
}
