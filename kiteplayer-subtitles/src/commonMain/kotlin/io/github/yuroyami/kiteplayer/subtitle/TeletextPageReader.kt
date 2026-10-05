package io.github.yuroyami.kiteplayer.subtitle

/**
 * Reads one subtitle page of an EBU teletext stream (ETSI EN 300 706), as DVB carries it in a
 * transport stream (ETSI EN 300 472), into text cues (#510).
 *
 * Each call to [read] takes one PES payload, the data identifier byte and then the data units, which
 * is what FFmpeg hands over as a `dvb_teletext` packet. A page shows from the PTS of the payload
 * that brings it, as VLC shows it, and not from the next page header, where FFmpeg's own decoder
 * waits; it ends at [SubtitleCue.OPEN_END], so the engine closes it at the next cue of its track.
 * A page erased with nothing on it becomes a cue with no spans, which draws nothing and clears the
 * one before. A page sent again unchanged makes no cue.
 *
 * On a subtitle or newsflash page only the boxed text shows, as on a television. The colours become
 * each span's colour, a background other than the black box becomes its background, and double
 * height keeps its proportion within the page: a page that mixes the two draws normal height at
 * half the size of double height, and a page of one height draws at the player's size. Rows in the
 * top half of the page draw at the top and the rest at the bottom.
 *
 * The characters come from the national option the page header sets, inside the region that an
 * X/28/0 or M/29/0 packet names, as libzvbi chooses them. With neither packet the region comes from
 * [language], so a Polish or Turkish channel reads right where a receiver set for Western Europe
 * would get the wrong letters. Latin, Cyrillic, Greek and Hebrew are read; Arabic is not.
 *
 * One reader belongs to one track's decoder and is not safe to share between threads.
 *
 * @param page the page as teletext numbers it, in hexadecimal digits, so page 888 is `0x888`, from
 *   `0x100` to `0x8FF`. Null shows the first page whose header marks it as a subtitle page.
 * @param language the ISO 639-2 code the stream's descriptor gives the page, or null.
 */
public class TeletextPageReader(
    private val page: Int? = null,
    private val language: String? = null,
) {
    init {
        require(page == null || page in 0x100..0x8FF) { "a teletext page is 0x100 to 0x8FF, not 0x${page?.toString(16)}" }
    }

    /**
     * The cues that [payload], one PES payload presented at [ptsMicros], puts on screen, in order,
     * or none when it changes nothing. Never throws on damaged data: a row that cannot be corrected
     * is skipped, as a television skips it.
     */
    public fun read(payload: ByteArray, ptsMicros: Long): List<SubtitleCue.Text> = emptyList()

    /** What the reader still holds at the end of the stream, which is an erase that nothing followed. */
    public fun end(): List<SubtitleCue.Text> = emptyList()

    /** Forgets every page and row, as after a seek, so the next page shows whatever it holds. */
    public fun reset() {}
}
