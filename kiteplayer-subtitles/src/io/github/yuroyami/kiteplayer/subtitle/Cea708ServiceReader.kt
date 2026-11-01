package io.github.yuroyami.kiteplayer.subtitle

/**
 * Reads one CEA-708 caption service (CEA-708-E, section 8) into text cues (#678).
 *
 * Each call to [read] takes the bytes of one service that a picture completed, which the engine
 * splits out of the DTVCC packets inside the picture, and answers the cues that put the screen as
 * it now stands from [read]'s time on: one cue for each visible window that holds text, at the
 * window's anchor, all with the same start and [SubtitleCue.OPEN_END], so the engine closes them
 * together at the next change. A screen that empties gives one cue with no spans, which clears the
 * one before, and bytes that change nothing on screen give no cue, so text written into a hidden
 * window shows only when a command displays it, as pop-on captions mean it to.
 *
 * What is read:
 *
 * - The eight windows, with DefineWindow, the window and pen styles it names, SetCurrentWindow,
 *   Clear, Display, Hide, Toggle and DeleteWindows, Reset, and SetWindowAttributes for the fill
 *   colour and word wrap.
 * - The pen: SetPenAttributes for its size, italics, underline and edge, SetPenColor for the text,
 *   background and edge colours with their opacity, and SetPenLocation for the row and column.
 * - The text: G0 (ASCII, with 0x7F as a music note), G1 (Latin-1), the G2 symbols and the G3
 *   caption icon, and the backspace, form feed, carriage return and horizontal carriage return of
 *   C0. A carriage return on a window's last row scrolls it up a row, as roll-up captions need.
 * - Delay and DelayCancel: the commands after a Delay take effect when it runs out, or at a
 *   DelayCancel or a Reset, at the time of the packet that ends it or at the end of the delay.
 *
 * What is not: text printed in a direction other than left to right, which the standard allows and
 * broadcasts do not use; the font style, because no generic family names a face on every platform,
 * so a caption keeps the player's own; flashing, borders, display effects and the pen's text tags
 * and offset; and the justification of a window's lines, which follow the horizontal part of the
 * window's anchor. Anchors in absolute positions are read on the 16:9 grid of 210 columns.
 *
 * One reader belongs to one track's decoder and is not safe to share between threads.
 */
internal class Cea708ServiceReader {

    private val windows = arrayOfNulls<Window>(WINDOW_COUNT)
    private var current: Window? = null

    /** The end of a Delay that is running, or null. The bytes it holds back wait in [held]. */
    private var delayEnd: Long? = null
    private var held = ByteArray(0)

    /** The start of a code the last read ended inside, kept for the next. */
    private var carry = ByteArray(0)

    /** What the cues last emitted show, as the screen of [render] gives it, with no time. */
    private var shown: List<SubtitleCue.Text> = emptyList()

    /** The cues this read gives, in order. */
    private val out = ArrayList<SubtitleCue.Text>()

    /**
     * The cues that [bytes], one service's bytes as a picture completed them at [ptsMicros], put on
     * screen, or none when they change nothing. Never throws on damaged data: an unknown code is
     * read past, and a command that names a window that does not exist does nothing.
     */
    fun read(bytes: ByteArray, ptsMicros: Long): List<SubtitleCue.Text> {
        out.clear()
        val delay = delayEnd
        if (delay != null && ptsMicros >= delay) {
            // The delay ran out before this packet: what it held takes effect at its end.
            val waiting = held
            held = ByteArray(0)
            delayEnd = null
            run(waiting, delay)
        }
        val input = if (carry.isEmpty()) bytes else carry + bytes
        carry = ByteArray(0)
        run(input, ptsMicros)
        return out.toList()
    }

    /** Forgets every window and every held byte, as after a seek. */
    fun reset() {
        windows.fill(null)
        current = null
        delayEnd = null
        held = ByteArray(0)
        carry = ByteArray(0)
        shown = emptyList()
    }

    /** Interprets [bytes] at [stamp], holding back what a Delay asks for, and settles the screen. */
    private fun run(bytes: ByteArray, stamp: Long) {
        var at = 0
        while (at < bytes.size) {
            val length = codeLength(bytes, at)
            if (length == 0) {
                // The code's parameters come with the next packet.
                carry = bytes.copyOfRange(at, bytes.size)
                break
            }
            val code = bytes[at].toInt() and 0xFF
            if (delayEnd != null) {
                when (code) {
                    // A cancel or a reset ends the delay now, and what it held takes effect first.
                    DLC, RST -> {
                        val waiting = held
                        held = ByteArray(0)
                        delayEnd = null
                        if (code == DLC) run(waiting, stamp) else resetService()
                    }
                    else -> held += bytes.copyOfRange(at, at + length)
                }
                at += length
                continue
            }
            if (code == DLY) {
                delayEnd = stamp + (bytes[at + 1].toInt() and 0xFF) * TENTH_OF_A_SECOND
                settle(stamp)
                at += length
                continue
            }
            execute(bytes, at)
            at += length
        }
        settle(stamp)
    }

    /**
     * The length of the code at [at], its parameters included, or 0 when they run past the end of
     * [bytes].
     */
    private fun codeLength(bytes: ByteArray, at: Int): Int {
        val code = bytes[at].toInt() and 0xFF
        val length = when {
            code == EXT1 -> {
                if (at + 1 >= bytes.size) return 0
                val extended = bytes[at + 1].toInt() and 0xFF
                2 + when (extended) {
                    in 0x00..0x07 -> 0
                    in 0x08..0x0F -> 1
                    in 0x10..0x17 -> 2
                    in 0x18..0x1F -> 3
                    in 0x80..0x87 -> 4
                    in 0x88..0x8F -> 5
                    in 0x90..0x9F -> {
                        if (at + 2 >= bytes.size) return 0
                        1 + (bytes[at + 2].toInt() and 0x3F)
                    }
                    else -> 0
                }
            }
            code in 0x11..0x17 -> 2
            code in 0x18..0x1F -> 3
            code < 0x20 -> 1
            code in 0x80..0x87 -> 1
            code in 0x88..0x8D -> 2
            code == DLC || code == RST -> 1
            code == SPA -> 3
            code == SPC -> 4
            code == SPL -> 3
            code in 0x93..0x96 -> 1
            code == SWA -> 5
            code in 0x98..0x9F -> 7
            else -> 1
        }
        return if (at + length > bytes.size) 0 else length
    }

    /** Runs the code at [at], whose parameters [codeLength] found whole. */
    private fun execute(bytes: ByteArray, at: Int) {
        val code = bytes[at].toInt() and 0xFF
        fun parameter(index: Int): Int = bytes[at + 1 + index].toInt() and 0xFF
        when {
            code == EXT1 -> extended(bytes, at + 1)
            code < 0x20 -> control(code)
            code < 0x80 -> write(if (code == 0x7F) MUSIC_NOTE else code.toChar())
            code < 0xA0 -> command(code, ::parameter)
            else -> write(code.toChar())
        }
    }

    /** A code of the extended set: the G2 symbols and the G3 icon. C2 and C3 hold nothing to read. */
    private fun extended(bytes: ByteArray, at: Int) {
        val code = bytes[at].toInt() and 0xFF
        when {
            code in 0x20..0x7F -> G2[code]?.let(::write)
            code == 0xA0 -> write(CAPTION_ICON)
        }
    }

    private fun control(code: Int) {
        val window = current ?: return
        when (code) {
            BS -> window.backspace()
            FF -> window.clear()
            CR -> window.carriageReturn()
            HCR -> window.clearRow()
        }
    }

    private fun write(char: Char) {
        current?.write(char)
    }

    private fun command(code: Int, parameter: (Int) -> Int) {
        when (code) {
            in 0x80..0x87 -> windows[code - 0x80]?.let { current = it }
            CLW -> forEachWindow(parameter(0)) { it.clear() }
            DSW -> forEachWindow(parameter(0)) { it.visible = true }
            HDW -> forEachWindow(parameter(0)) { it.visible = false }
            TGW -> forEachWindow(parameter(0)) { it.visible = !it.visible }
            DLW -> for (id in 0 until WINDOW_COUNT) {
                if (parameter(0) and (1 shl id) != 0) {
                    if (current === windows[id]) current = null
                    windows[id] = null
                }
            }
            RST -> resetService()
            SPA -> current?.let { window ->
                val first = parameter(0)
                val second = parameter(1)
                window.pen = window.pen.copy(
                    size = first and 0x03,
                    italic = second and 0x80 != 0,
                    underline = second and 0x40 != 0,
                    edge = (second ushr 3) and 0x07,
                )
            }
            SPC -> current?.let { window ->
                window.pen = window.pen.copy(
                    foreground = colorOf(parameter(0)),
                    background = colorOf(parameter(1)),
                    edgeColor = colorOf(parameter(2) and 0x3F),
                )
            }
            SPL -> current?.moveTo(row = parameter(0) and 0x0F, column = parameter(1) and 0x3F)
            SWA -> current?.let { window ->
                window.fill = colorOf(parameter(0))
                window.wordWrap = parameter(2) and 0x40 != 0
            }
            in 0x98..0x9F -> define(code - 0x98, parameter)
        }
    }

    private inline fun forEachWindow(mask: Int, action: (Window) -> Unit) {
        for (id in 0 until WINDOW_COUNT) if (mask and (1 shl id) != 0) windows[id]?.let(action)
    }

    /** DefineWindow: makes window [id], or changes the one there, and makes it the current window. */
    private fun define(id: Int, parameter: (Int) -> Int) {
        val first = parameter(0)
        val window = windows[id]
        val made = window ?: Window().also { windows[id] = it }
        made.visible = first and 0x20 != 0
        made.priority = first and 0x07
        val vertical = parameter(1)
        made.relative = vertical and 0x80 != 0
        made.anchorVertical = vertical and 0x7F
        made.anchorHorizontal = parameter(2)
        made.anchorPoint = (parameter(3) ushr 4).coerceAtMost(8)
        made.resize(rows = (parameter(3) and 0x0F) + 1, columns = (parameter(4) and 0x3F) + 1)
        // A new window takes style 1 for style 0; a window that exists keeps its own.
        val windowStyle = (parameter(5) ushr 3) and 0x07
        val penStyle = parameter(5) and 0x07
        if (window == null || windowStyle != 0) made.applyWindowStyle(windowStyle.coerceAtLeast(1))
        if (window == null || penStyle != 0) made.pen = PEN_STYLES[penStyle.coerceAtLeast(1) - 1]
        current = made
    }

    private fun resetService() {
        windows.fill(null)
        current = null
    }

    /** Emits the screen at [stamp] when it differs from the one last emitted. */
    private fun settle(stamp: Long) {
        val screen = render()
        if (screen == shown) return
        shown = screen
        // Two changes at one time leave only the last, because the engine shows a start's group whole.
        out.removeAll { it.startMicros == stamp }
        if (screen.isEmpty()) {
            out += SubtitleCue.Text(stamp, SubtitleCue.OPEN_END, emptyList())
        } else {
            screen.forEach { out += it.copy(startMicros = stamp) }
        }
    }

    /** One cue for each visible window with text, the highest priority last, with no time. */
    private fun render(): List<SubtitleCue.Text> = windows.withIndex()
        .mapNotNull { (id, window) -> window?.takeIf { it.visible }?.let { id to it } }
        .sortedWith(compareByDescending<Pair<Int, Window>> { it.second.priority }.thenBy { it.first })
        .mapNotNull { (_, window) -> window.cue() }

    /** The pen a window writes its next characters with. */
    private data class Pen(
        val size: Int = PEN_STANDARD,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val edge: Int = EDGE_NONE,
        val foreground: Int = WHITE,
        val background: Int = BLACK,
        val edgeColor: Int = BLACK,
    )

    /** One window: where it sits, how it looks, and its rows of characters, each with its pen. */
    private class Window {
        var visible = false
        var priority = 0
        var relative = false
        var anchorVertical = 0
        var anchorHorizontal = 0
        var anchorPoint = 0
        var rows = 1
        var columns = 1
        var fill = BLACK
        var wordWrap = false
        var pen = Pen()

        private val text = Array(MAX_ROWS) { CharArray(MAX_COLUMNS) }
        private val pens = Array(MAX_ROWS) { arrayOfNulls<Pen>(MAX_COLUMNS) }
        private var row = 0
        private var column = 0

        fun applyWindowStyle(style: Int) {
            fill = if (style == 2 || style == 5) TRANSPARENT else BLACK
            wordWrap = style in 4..6
        }

        /** A new size keeps the cursor's row on screen, scrolling the rows above it away. */
        fun resize(rows: Int, columns: Int) {
            this.rows = rows.coerceAtMost(MAX_ROWS)
            this.columns = columns.coerceAtMost(MAX_COLUMNS)
            while (row >= this.rows) scroll()
        }

        fun clear() {
            for (index in 0 until MAX_ROWS) clearRow(index)
            row = 0
            column = 0
        }

        fun clearRow() {
            clearRow(row)
            column = 0
        }

        private fun clearRow(index: Int) {
            text[index].fill(EMPTY)
            pens[index].fill(null)
        }

        fun moveTo(row: Int, column: Int) {
            this.row = row.coerceAtMost(rows - 1)
            this.column = column.coerceAtMost(MAX_COLUMNS - 1)
        }

        fun backspace() {
            if (column == 0) return
            column--
            text[row][column] = EMPTY
            pens[row][column] = null
        }

        fun carriageReturn() {
            column = 0
            if (row + 1 < rows) row++ else scroll()
        }

        /** Moves every row up one and empties the last, the cursor staying on its row. */
        private fun scroll() {
            for (index in 0 until MAX_ROWS - 1) {
                text[index + 1].copyInto(text[index])
                pens[index + 1].copyInto(pens[index])
            }
            clearRow(MAX_ROWS - 1)
            if (row > 0 && row >= rows) row--
        }

        fun write(char: Char) {
            if (column >= columns && wordWrap) {
                // The space that breaks the line is not drawn at the next one's start.
                if (char == ' ') {
                    carriageReturn()
                    return
                }
                wrap()
            }
            if (column >= MAX_COLUMNS) return
            text[row][column] = char
            pens[row][column] = pen
            column++
        }

        /**
         * Moves the word being written, the cells after the row's last space, to the start of the
         * next row. A word as long as the row breaks where it is.
         */
        private fun wrap() {
            var space = column - 1
            while (space >= 0 && text[row][space] != ' ') space--
            val start = if (space < 0) column else space + 1
            val moved = text[row].copyOfRange(start, column)
            val movedPens = pens[row].copyOfRange(start, column)
            for (index in start until column) {
                text[row][index] = EMPTY
                pens[row][index] = null
            }
            carriageReturn()
            moved.copyInto(text[row])
            movedPens.copyInto(pens[row])
            column = moved.size
        }

        /** The window as a cue, or null when it holds no text to show. */
        fun cue(): SubtitleCue.Text? {
            val shownRows = (0 until rows).filter { index -> text[index].any(::isInk) }
            if (shownRows.isEmpty()) return null
            val first = shownRows.first()
            val last = shownRows.last()
            // Rows keep their columns against each other, from the leftmost character of any.
            val lead = shownRows.minOf { index -> text[index].indexOfFirst(::isInk) }
            val spans = ArrayList<StyledSpan>()
            val run = StringBuilder()
            var runPen: Pen? = null
            fun flush() {
                val style = runPen
                if (run.isNotEmpty() && style != null) spans += StyledSpan(run.toString(), styleOf(style))
                run.clear()
            }
            for (index in first..last) {
                // A line break takes the pen of the text before it, and a row with none is one space.
                if (index > first) run.append('\n')
                val end = text[index].indexOfLast(::isInk)
                if (end < 0) {
                    run.append(' ')
                    continue
                }
                for (cell in lead..end) {
                    val char = text[index][cell]
                    val cellPen = pens[index][cell] ?: runPen ?: pen
                    if (cellPen != runPen) {
                        flush()
                        runPen = cellPen
                    }
                    run.append(if (char == EMPTY) ' ' else char)
                }
            }
            flush()
            return SubtitleCue.Text(
                startMicros = 0,
                endMicros = SubtitleCue.OPEN_END,
                spans = spans,
                layout = CueLayout(
                    alignment = ANCHORS[anchorPoint],
                    positionX = if (relative) anchorHorizontal.coerceAtMost(99) / 99f else anchorHorizontal.coerceAtMost(209) / 209f,
                    positionY = if (relative) anchorVertical.coerceAtMost(99) / 99f else anchorVertical.coerceAtMost(74) / 74f,
                ),
                layer = MAX_PRIORITY - priority,
            )
        }

        private fun styleOf(pen: Pen): CueStyle {
            val background = if (pen.background ushr 24 != 0) pen.background else fill
            return CueStyle(
                italic = pen.italic,
                underline = pen.underline,
                primaryColor = pen.foreground,
                backgroundColor = background,
                relativeSize = when (pen.size) {
                    PEN_SMALL -> SMALL_SIZE
                    PEN_LARGE -> LARGE_SIZE
                    else -> 1f
                },
                outlineColor = pen.edgeColor,
                outlineWidthPx = if (pen.edge == EDGE_UNIFORM) 2f else 0f,
                shadowColor = if (pen.edge in SHADOW_EDGES) pen.edgeColor else TRANSPARENT,
                shadowOffsetPx = when (pen.edge) {
                    EDGE_RAISED -> 1f
                    EDGE_DEPRESSED -> -1f
                    EDGE_LEFT_SHADOW -> -2f
                    EDGE_RIGHT_SHADOW -> 2f
                    else -> 0f
                },
            )
        }
    }

    private companion object {
        const val WINDOW_COUNT = 8
        const val MAX_ROWS = 15
        const val MAX_COLUMNS = 42
        const val MAX_PRIORITY = 7
        const val TENTH_OF_A_SECOND = 100_000L

        /** A cell nothing was written in. */
        const val EMPTY = '\u0000'

        /** True for a character that draws, which a cell nothing was written in and a space do not. */
        fun isInk(char: Char): Boolean = char != EMPTY && !char.isWhitespace()

        // C0.
        const val EXT1 = 0x10
        const val BS = 0x08
        const val FF = 0x0C
        const val CR = 0x0D
        const val HCR = 0x0E

        // C1.
        const val CLW = 0x88
        const val DSW = 0x89
        const val HDW = 0x8A
        const val TGW = 0x8B
        const val DLW = 0x8C
        const val DLY = 0x8D
        const val DLC = 0x8E
        const val RST = 0x8F
        const val SPA = 0x90
        const val SPC = 0x91
        const val SPL = 0x92
        const val SWA = 0x97

        const val PEN_SMALL = 0
        const val PEN_STANDARD = 1
        const val PEN_LARGE = 2
        const val SMALL_SIZE = 0.8f
        const val LARGE_SIZE = 1.25f

        const val EDGE_NONE = 0
        const val EDGE_RAISED = 1
        const val EDGE_DEPRESSED = 2
        const val EDGE_UNIFORM = 3
        const val EDGE_LEFT_SHADOW = 4
        const val EDGE_RIGHT_SHADOW = 5
        val SHADOW_EDGES = setOf(EDGE_RAISED, EDGE_DEPRESSED, EDGE_LEFT_SHADOW, EDGE_RIGHT_SHADOW)

        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
        const val TRANSPARENT = 0x00000000

        const val MUSIC_NOTE = '♪'

        /** The G3 caption icon, as Media3 draws it. */
        const val CAPTION_ICON = '㏄'

        /** Anchor points 0 to 8: top, middle and bottom rows of left, centre and right. */
        val ANCHORS = arrayOf(
            CueAlignment.TopLeft, CueAlignment.TopCenter, CueAlignment.TopRight,
            CueAlignment.MiddleLeft, CueAlignment.MiddleCenter, CueAlignment.MiddleRight,
            CueAlignment.BottomLeft, CueAlignment.BottomCenter, CueAlignment.BottomRight,
        )

        /**
         * The predefined pen styles 1 to 7 (CEA-708-E table 27): white text on black, then the same
         * with a transparent background and a black uniform edge for 6 and 7. Their fonts differ,
         * which is not read.
         */
        val PEN_STYLES = Array(7) { index ->
            if (index >= 5) Pen(background = TRANSPARENT, edge = EDGE_UNIFORM) else Pen()
        }

        /** The G2 symbols that have a character (CEA-708-E table 16). */
        val G2: Map<Int, Char> = mapOf(
            0x20 to ' ', 0x21 to ' ', 0x25 to '…', 0x2A to 'Š', 0x2C to 'Œ',
            0x30 to '█', 0x31 to '‘', 0x32 to '’', 0x33 to '“', 0x34 to '”',
            0x35 to '•', 0x39 to '™', 0x3A to 'š', 0x3C to 'œ', 0x3D to '℠',
            0x3F to 'Ÿ', 0x76 to '⅛', 0x77 to '⅜', 0x78 to '⅝', 0x79 to '⅞',
            0x7A to '│', 0x7B to '┐', 0x7C to '└', 0x7D to '─', 0x7E to '┘',
            0x7F to '┌',
        )

        /**
         * A colour of SetPenColor or SetWindowAttributes: two bits of opacity, then two each of red,
         * green and blue. Solid and flashing are opaque, translucent is half and transparent none.
         */
        fun colorOf(value: Int): Int {
            val alpha = when ((value ushr 6) and 0x03) {
                0, 1 -> 0xFF
                2 -> 0x80
                else -> 0x00
            }
            fun level(bits: Int) = bits * 0x55
            return (alpha shl 24) or (level((value ushr 4) and 3) shl 16) or (level((value ushr 2) and 3) shl 8) or level(value and 3)
        }
    }
}
