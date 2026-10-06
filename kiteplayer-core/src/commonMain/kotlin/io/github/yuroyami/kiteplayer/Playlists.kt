package io.github.yuroyami.kiteplayer

/**
 * Playlist files read into queue items (#490): the M3U lists people keep their music in and other
 * players export, PLS lists, and XSPF lists.
 *
 * - M3U and M3U8 that are not HLS, which always carries `#EXT-X-` tags: each line that is not a
 *   comment names an item. `#EXTINF` gives the title of the item after it, `#EXTART` and `#EXTALB`
 *   its artist and album, and `#EXTVLCOPT` lines for `http-referrer` and `http-user-agent` its
 *   `Referer` and `User-Agent` headers, as IPTV lists write them.
 * - PLS: the `FileN` entries in their number order, with their `TitleN`.
 * - XSPF: each `<track>`'s first `<location>`, with its `<title>`, `<creator>` and `<album>`.
 *
 * A relative address is resolved against the list's own: a path beside a list on disk, with the
 * backslashes of a list written on Windows read as separators, and an address beside a list on a
 * server. A `file://` address becomes the path it names. The text is read as it is given; see
 * [KitePlayer.readPlaylist] for a list's bytes.
 */
public object Playlists {

    /**
     * The items [text] names, its relative addresses resolved against [base], the list's own
     * address. Null when [text] is no playlist this reads, an HLS playlist included, which plays as
     * one item. An empty PLS or XSPF list is an empty list.
     */
    public fun parse(text: String, base: String): List<MediaItem>? = null
}

/**
 * A playlist that cannot become a queue: one that cannot be read, that is no playlist, that names
 * nothing, or that names itself, directly or through a list it names. [uri] is the list's address.
 */
public class PlaylistException(message: String, public val uri: String) : Exception(message)
