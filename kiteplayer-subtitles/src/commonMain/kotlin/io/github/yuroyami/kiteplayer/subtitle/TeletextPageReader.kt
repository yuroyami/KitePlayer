package io.github.yuroyami.kiteplayer.subtitle

import io.github.yuroyami.kiteplayer.subtitle.TeletextCoding.hamming2418
import io.github.yuroyami.kiteplayer.subtitle.TeletextCoding.hamming84
import io.github.yuroyami.kiteplayer.subtitle.TeletextCoding.oddParity
import io.github.yuroyami.kiteplayer.subtitle.TeletextCoding.reversed

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
    page: Int? = null,
    private val language: String? = null,
) {
    init {
        require(page == null || page in 0x100..0x8FF) { "a teletext page is 0x100 to 0x8FF, not 0x${page?.toString(16)}" }
    }

    /** The page this reader shows, or 0 until a subtitle page names itself when none was given. */
    private var target: Int = page ?: 0

    /** The page each magazine is sending now, by its magazine number with 8 as 0, or -1 between pages. */
    private val sending = IntArray(8) { -1 }

    /** The character set each magazine's M/29/0 packet names, and its second set, or -1. */
    private val magazineSet = IntArray(8) { -1 }
    private val magazineSecondSet = IntArray(8) { -1 }

    /** The page as it stands: rows 1 to 23 after the bit reversal, and its X/26 triplets by designation. */
    private val rows = arrayOfNulls<IntArray>(24)
    private val enhancements = arrayOfNulls<IntArray>(16)
    private var pageSet = -1
    private var pageSecondSet = -1

    /** Whether a header of the page has arrived since the start or the last [reset]. */
    private var heard = false
    private var subtitle = false
    private var newsflash = false
    private var inhibited = false
    private var national = 0
    private var rowsSinceHeader = 0

    /** What the cues last emitted show, which is nothing at the start and after a [reset]. */
    private var shown: List<Screen> = emptyList()

    /** The PTS of an erase held for one payload, in case the page's rows follow in the next. */
    private var heldClear: Long? = null

    /**
     * The cues that [payload], one PES payload presented at [ptsMicros], puts on screen, in order,
     * or none when it changes nothing. Never throws on damaged data: a row that cannot be corrected
     * is skipped, as a television skips it.
     */
    public fun read(payload: ByteArray, ptsMicros: Long): List<SubtitleCue.Text> {
        if (payload.isEmpty() || !carriesTeletext(payload[0].toInt() and 0xFF)) return emptyList()
        var touched = false
        var ended = false
        var offset = 1
        while (offset + 2 <= payload.size) {
            val unit = payload[offset].toInt() and 0xFF
            val length = payload[offset + 1].toInt() and 0xFF
            val start = offset + 2
            offset = start + length
            if (offset > payload.size) break
            if ((unit == SUBTITLE_DATA || unit == NON_SUBTITLE_DATA) && length == UNIT_LENGTH) {
                val outcome = packet(payload, start)
                touched = touched || outcome and TOUCHED != 0
                ended = ended || outcome and ENDED != 0
            }
        }
        return settle(ptsMicros, touched, ended)
    }

    /** What the reader still holds at the end of the stream, which is an erase that nothing followed. */
    public fun end(): List<SubtitleCue.Text> {
        val held = heldClear ?: return emptyList()
        heldClear = null
        return show(render(), held)
    }

    /** Forgets every page and row, as after a seek, so the next page shows whatever it holds. */
    public fun reset() {
        sending.fill(-1)
        magazineSet.fill(-1)
        magazineSecondSet.fill(-1)
        erase()
        heard = false
        shown = emptyList()
        heldClear = null
    }

    /** One 44-byte data unit at [start]: the line's own byte, its framing code, its address and 40 bytes. */
    private fun packet(payload: ByteArray, start: Int): Int {
        val low = hamming84(reversed(payload[start + 2]))
        val high = hamming84(reversed(payload[start + 3]))
        if (low < 0 || high < 0) return 0
        val address = low or (high shl 4)
        val magazine = address and 7
        val row = address shr 3
        val data = IntArray(40) { reversed(payload[start + 4 + it]) }
        if (row == 0) return header(magazine, data)
        if (row == 29) {
            magazineDesignation(magazine, data)
            return 0
        }
        if (!heard || sending[magazine] != target) return 0
        when (row) {
            in 1..23 -> {
                store(row, data)
                rowsSinceHeader++
            }
            26 -> {
                val designation = hamming84(data[0])
                if (designation < 0) return 0
                enhancements[designation] = IntArray(13) { hamming2418(data[1 + it * 3], data[2 + it * 3], data[3 + it * 3]) }
            }
            28 -> pageDesignation(data)
            else -> return 0
        }
        return TOUCHED
    }

    /**
     * A page header. Any header ends the page its magazine was sending, and a header in serial mode
     * ends every magazine's, which is how a receiver knows a page is whole.
     */
    private fun header(magazine: Int, data: IntArray): Int {
        val fields = IntArray(8) { hamming84(data[it]) }
        val serial = fields[7] >= 0 && fields[7] and 1 == 1
        var outcome = 0
        for (each in 0..7) {
            if (!serial && each != magazine) continue
            if (heard && sending[each] == target) outcome = ENDED
            sending[each] = -1
        }
        if (fields.any { it < 0 }) return outcome
        val number = ((if (magazine == 0) 8 else magazine) shl 8) or (fields[1] shl 4) or fields[0]
        // Page FF fills time and starts no page.
        if (number and 0xFF == 0xFF) return outcome
        sending[magazine] = number
        val isSubtitle = fields[5] and 8 != 0
        if (target == 0 && isSubtitle) target = number
        if (number != target) return outcome
        if (fields[3] and 8 != 0) erase()
        subtitle = isSubtitle
        newsflash = fields[5] and 4 != 0
        inhibited = fields[6] and 8 != 0
        // C12, C13 and C14, with C14 the low bit of the option.
        val option = fields[7] shr 1 and 7
        national = (option and 1 shl 2) or (option and 2) or (option shr 2 and 1)
        heard = true
        rowsSinceHeader = 0
        return outcome or TOUCHED
    }

    private fun erase() {
        rows.fill(null)
        enhancements.fill(null)
        pageSet = -1
        pageSecondSet = -1
    }

    /** Keeps the earlier byte where the new one fails its parity, so a resent row heals a damaged one. */
    private fun store(row: Int, data: IntArray) {
        val earlier = rows[row]
        if (earlier != null) {
            for (column in 0..39) if (oddParity(data[column]) < 0 && oddParity(earlier[column]) >= 0) data[column] = earlier[column]
        }
        rows[row] = data
    }

    /** X/28/0 or X/28/4, Format 1: the page's own character sets, for a page of basic level one. */
    private fun pageDesignation(data: IntArray) {
        val designation = hamming84(data[0])
        if (designation != 0 && designation != 4) return
        val first = hamming2418(data[1], data[2], data[3])
        if (first < 0 || first and 0xF != 0) return
        pageSet = first shr 7 and 0x7F
        val second = hamming2418(data[4], data[5], data[6])
        pageSecondSet = if (second < 0) -1 else (first shr 14 and 0xF) or (second and 7 shl 4)
    }

    /** M/29/0 or M/29/4: the character sets of every page of the magazine that names none. */
    private fun magazineDesignation(magazine: Int, data: IntArray) {
        val designation = hamming84(data[0])
        if (designation != 0 && designation != 4) return
        val first = hamming2418(data[1], data[2], data[3])
        if (first < 0) return
        magazineSet[magazine] = first shr 7 and 0x7F
        val second = hamming2418(data[4], data[5], data[6])
        magazineSecondSet[magazine] = if (second < 0) -1 else (first shr 14 and 0xF) or (second and 7 shl 4)
    }

    /**
     * Turns the payload's effect on the page into cues. An erase with no row yet is held, because
     * a broadcaster can send a page's header and its rows in separate payloads, and clearing at the
     * header would make every resent page blink. The hold ends at the next payload that brings
     * nothing for the page, or when the page ends, and the clear then keeps the erase's own time.
     */
    private fun settle(ptsMicros: Long, touched: Boolean, ended: Boolean): List<SubtitleCue.Text> {
        if (!heard) return emptyList()
        if (!touched && !ended) {
            val held = heldClear ?: return emptyList()
            heldClear = null
            return show(render(), held)
        }
        val screen = render()
        // The page's own header ends its last sending and starts the next, so what decides is
        // whether it is still being sent.
        if (screen.isEmpty() && rowsSinceHeader == 0 && sending[target shr 8 and 7] == target) {
            if (heldClear == null) heldClear = ptsMicros
            return emptyList()
        }
        val start = heldClear?.takeIf { screen.isEmpty() } ?: ptsMicros
        heldClear = null
        return show(screen, start)
    }

    private fun show(screen: List<Screen>, start: Long): List<SubtitleCue.Text> {
        if (screen == shown) return emptyList()
        shown = screen
        if (screen.isEmpty()) return listOf(SubtitleCue.Text(start, SubtitleCue.OPEN_END, emptyList()))
        return screen.map { SubtitleCue.Text(start, SubtitleCue.OPEN_END, it.spans, CueLayout(alignment = it.alignment)) }
    }

    /** One block of the page, the rows of its top half or of its bottom half. */
    private data class Screen(val alignment: CueAlignment, val spans: List<StyledSpan>)

    /** One shown character and how it looks. */
    private class Cell(val text: String, val foreground: Int, val background: Int, val doubleHeight: Boolean)

    private fun render(): List<Screen> {
        if (inhibited) return emptyList()
        val magazine = target shr 8 and 7
        val designation = when {
            pageSet >= 0 -> pageSet
            magazineSet[magazine] >= 0 -> magazineSet[magazine]
            else -> TeletextCharacters.region(language)
        }
        val secondDesignation = if (pageSet >= 0) pageSecondSet else magazineSecondSet[magazine]
        val charset = TeletextCharacters.select(designation, national)
        val second = if (secondDesignation >= 0) TeletextCharacters.select(secondDesignation, national) else null
        val placed = placedCharacters(charset)

        val lines = ArrayList<Pair<Int, List<Cell?>>>()
        var row = 1
        while (row <= 23) {
            val data = rows[row]
            if (data == null) {
                row++
                continue
            }
            val (cells, doubleHeight) = cells(row, data, charset, second, placed)
            if (cells.any { it != null && it.text.isNotBlank() }) lines += row to cells
            // A double height row covers the one below it, which a television never draws.
            row += if (doubleHeight) 2 else 1
        }
        if (lines.isEmpty()) return emptyList()
        val visible = lines.flatMap { (_, cells) -> cells.filterNotNull().filter { it.text.isNotBlank() } }
        val mixed = visible.any { it.doubleHeight } && visible.any { !it.doubleHeight }
        val top = lines.filter { it.first < 12 }.map { it.second }
        val bottom = lines.filter { it.first >= 12 }.map { it.second }
        return buildList {
            if (top.isNotEmpty()) add(Screen(CueAlignment.TopCenter, spans(top, mixed)))
            if (bottom.isNotEmpty()) add(Screen(CueAlignment.BottomCenter, spans(bottom, mixed)))
        }
    }

    /**
     * The characters the page's X/26 packets place over its rows, by row and column, read in the
     * order of their designations as a television applies them.
     */
    private fun placedCharacters(charset: TeletextCharacters.Charset): Map<Int, String> {
        val placed = HashMap<Int, String>()
        var activeRow = 0
        for (triplets in enhancements) {
            if (triplets == null) continue
            for (triplet in triplets) {
                if (triplet < 0) continue
                val address = triplet and 0x3F
                val mode = triplet shr 6 and 0x1F
                val data = triplet shr 11 and 0x7F
                if (address >= 40) {
                    when (mode) {
                        // Full row colour and set active position move to a row; 40 is row 24.
                        0x01, 0x04 -> activeRow = if (address == 40) 24 else address - 40
                        0x07 -> if (address == 0x3F) activeRow = 0
                        // The end of an object's definition, and the termination marker.
                        0x15, 0x16, 0x17, 0x1F -> return placed
                    }
                    continue
                }
                if (data < 0x20) continue
                val key = activeRow * 40 + address
                when (mode) {
                    0x0F -> placed[key] = charset.g2(data)
                    0x10 -> placed[key] = if (data == 0x2A) "@" else TeletextCharacters.LATIN_NO_SUBSET.g0(data)
                    in 0x11..0x1F -> placed[key] = TeletextCharacters.compose(TeletextCharacters.LATIN_NO_SUBSET.g0(data), mode - 0x10)
                }
            }
        }
        return placed
    }

    /**
     * The row's characters as a television shows them, null for a cell that shows nothing, and
     * whether it asks for double height. The spacing attributes act between characters and show as
     * spaces themselves.
     */
    private fun cells(
        row: Int,
        data: IntArray,
        charset: TeletextCharacters.Charset,
        second: TeletextCharacters.Charset?,
        placed: Map<Int, String>,
    ): Pair<List<Cell?>, Boolean> {
        val boxedOnly = subtitle || newsflash
        var foreground = WHITE
        var background = BLACK
        var boxed = false
        var doubleHeight = false
        var rowIsDouble = false
        var mosaic = false
        var concealed = false
        var useSecond = false
        val cells = ArrayList<Cell?>(40)
        for (column in 0..39) {
            val code = oddParity(data[column])
            // Set-at attributes act on this cell.
            when (code) {
                0x0C -> doubleHeight = false
                0x18 -> concealed = true
                0x1C -> background = BLACK
                0x1D -> background = foreground
            }
            val text = placed[row * 40 + column] ?: when {
                code < 0x20 -> " "
                concealed -> " "
                mosaic && (code < 0x40 || code >= 0x60) -> " "
                useSecond && second != null -> second.g0(code)
                else -> charset.g0(code)
            }
            cells += if (boxed || !boxedOnly) Cell(text, foreground, background, doubleHeight) else null
            // Set-after attributes act from the next cell.
            when (code) {
                in 0x01..0x07 -> {
                    foreground = code
                    mosaic = false
                    concealed = false
                }
                in 0x11..0x17 -> {
                    foreground = code - 0x10
                    mosaic = true
                    concealed = false
                }
                0x0A -> boxed = false
                0x0B -> boxed = true
                0x0D, 0x0F -> {
                    doubleHeight = true
                    rowIsDouble = true
                }
                0x1B -> useSecond = !useSecond
            }
        }
        return cells to rowIsDouble
    }

    /** The lines of one block as spans, trimmed, each line after the first opening with a line break. */
    private fun spans(lines: List<List<Cell?>>, mixed: Boolean): List<StyledSpan> {
        val spans = ArrayList<StyledSpan>()
        for ((index, cells) in lines.withIndex()) {
            val first = cells.indexOfFirst { it != null && it.text.isNotBlank() }
            val last = cells.indexOfLast { it != null && it.text.isNotBlank() }
            var style: CueStyle? = null
            val text = StringBuilder(if (index > 0) "\n" else "")
            for (column in first..last) {
                val cell = cells[column]
                // A space takes the colour and size around it, so a box runs on between words,
                // but keeps its own background, so a box ends where the page ends it.
                if (cell == null || cell.text.isBlank()) {
                    val current = style
                    if (cell != null && current != null) {
                        val spaceStyle = current.copy(backgroundColor = backgroundOf(cell))
                        if (spaceStyle != current) {
                            spans.append(StyledSpan(text.toString(), current))
                            text.clear()
                            style = spaceStyle
                        }
                    }
                    text.append(' ')
                    continue
                }
                val cellStyle = style(cell, mixed)
                if (style != null && cellStyle != style) {
                    spans.append(StyledSpan(text.toString(), style))
                    text.clear()
                }
                style = cellStyle
                text.append(cell.text)
            }
            if (style != null) spans.append(StyledSpan(text.toString(), style))
        }
        return spans
    }

    /** Adds [span] to the list, joined to the last span when the two look the same. */
    private fun ArrayList<StyledSpan>.append(span: StyledSpan) {
        val last = lastOrNull()
        if (last != null && last.style == span.style) this[lastIndex] = last.copy(text = last.text + span.text) else add(span)
    }

    private fun style(cell: Cell, mixed: Boolean) = CueStyle(
        primaryColor = COLOURS[cell.foreground],
        backgroundColor = backgroundOf(cell),
        relativeSize = if (mixed && !cell.doubleHeight) 0.5f else 1f,
    )

    /** The black box is the player's own, so only another colour becomes a background. */
    private fun backgroundOf(cell: Cell): Int = if (cell.background == BLACK) 0 else COLOURS[cell.background]

    private companion object {
        const val SUBTITLE_DATA = 0x03
        const val NON_SUBTITLE_DATA = 0x02
        const val UNIT_LENGTH = 0x2C

        const val TOUCHED = 1
        const val ENDED = 2

        const val BLACK = 0
        const val WHITE = 7

        /** Black, red, green, yellow, blue, magenta, cyan and white, as ARGB. */
        val COLOURS = intArrayOf(
            0xFF000000.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFFFFFF00.toInt(),
            0xFF0000FF.toInt(), 0xFFFF00FF.toInt(), 0xFF00FFFF.toInt(), 0xFFFFFFFF.toInt(),
        )

        /** The EBU data identifiers of EN 300 472, and those of EN 301 775 that carry teletext. */
        fun carriesTeletext(identifier: Int): Boolean = identifier in 0x10..0x1F || identifier in 0x99..0x9B
    }
}
