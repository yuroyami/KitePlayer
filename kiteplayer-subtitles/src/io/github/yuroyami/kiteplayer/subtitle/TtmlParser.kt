package io.github.yuroyami.kiteplayer.subtitle

import io.github.yuroyami.kiteplayer.subtitle.xml.XmlElement
import io.github.yuroyami.kiteplayer.subtitle.xml.XmlMini
import io.github.yuroyami.kiteplayer.subtitle.xml.XmlSyntaxException
import io.github.yuroyami.kiteplayer.subtitle.xml.XmlText
import kotlin.math.roundToLong

/**
 * Reads TTML 1 and DFXP subtitle documents (#402, #492), the format broadcasters and streaming
 * services hand out, as text cues with their regions and styles. docs/subtitle-regions.md has the
 * whole contract, and what is not drawn.
 *
 * Each `p` element is one cue. Time expressions follow TTML 1, section 10.3.1: clock times with a
 * fraction or frames, and offset times in hours, minutes, seconds, milliseconds, frames or ticks,
 * against the document's frame and tick rates. An element's times count from its parent's begin
 * and end with its parent, so a `div` that begins at ten seconds moves every paragraph inside it,
 * and the children of a `timeContainer="seq"` element follow one another.
 *
 * A paragraph flowed into a region carries it as [CueLayout.region], with its place in the
 * document as [CueLayout.regionOrder]. A paragraph with no region, or one whose region cannot be
 * resolved, goes to the bottom of the picture as a SubRip line does.
 */
public object TtmlParser {

    /**
     * Whether [text] is a TTML or DFXP document: its root element is `tt` in one of the TTML
     * namespaces. Only the start of the text is read, and a file name plays no part.
     */
    public fun isTtml(text: String): Boolean {
        val limit = minOf(text.length, SNIFF_CHARS)
        var at = if (text.startsWith('﻿')) 1 else 0
        while (true) {
            while (at < limit && text[at].isWhitespace()) at++
            if (at >= limit || text[at] != '<') return false
            val next = when {
                text.startsWith("<?", at) -> text.indexOf("?>", at).let { if (it < 0) -1 else it + 2 }
                text.startsWith("<!--", at) -> text.indexOf("-->", at).let { if (it < 0) -1 else it + 3 }
                text.startsWith("<!", at) -> declarationEnd(text, at, limit)
                else -> break
            }
            if (next !in 0..limit) return false
            at = next
        }
        val end = text.indexOf('>', at)
        if (end !in 0 until limit) return false
        val tag = text.substring(at + 1, end)
        val name = tag.takeWhile { !it.isWhitespace() && it != '/' }
        if (name.substringAfter(':') != "tt") return false
        val declaration = if (':' in name) "xmlns:${name.substringBefore(':')}" else "xmlns"
        return attributeOf(tag.substring(name.length), declaration)?.trim() in NAMESPACES
    }

    /**
     * The cues of [text], sorted by start time, or none for a document this reader cannot read, as
     * every other reader answers a file it cannot read. Never throws on malformed input. A document
     * with more than 100,000 paragraphs keeps the first 100,000 in document order.
     */
    public fun parse(text: String): List<SubtitleCue.Text> = try {
        read(text)
    } catch (_: IllegalArgumentException) {
        emptyList()
    }

    /**
     * As [parse], but a document that is not TTML, is not well formed XML, or holds a time this
     * reader cannot read is refused with an [IllegalArgumentException] that says which.
     */
    public fun read(text: String): List<SubtitleCue.Text> = read(text, TtmlWork())

    internal fun read(text: String, work: TtmlWork, maxCues: Int = MAX_FILE_CUES): List<SubtitleCue.Text> {
        val root = try {
            XmlMini.parse(text.removePrefix("﻿"), XmlMini.Limits(keepContent = true))
        } catch (refusal: XmlSyntaxException) {
            throw IllegalArgumentException("not a well formed TTML document: ${refusal.message}", refusal)
        }
        require(root.name == "tt") { "not a TTML document: the root element is <${root.name}>" }
        return Reader(root, work, maxCues).cues()
    }

    /** One document's reading: its clock, its sizes, its styles and its regions. */
    private class Reader(private val root: XmlElement, work: TtmlWork, private val maxCues: Int) {
        private val clock = Clock(root)
        private val space = Space(root)
        private val head = root.child("head")
        private val styles = Styles(
            head?.child("styling")?.children("style").orEmpty().mapNotNull { style -> style.attr("id")?.let { it to style } }.toMap(),
            work,
        )
        private val regions: Map<String, RegionDef> =
            head?.child("layout")?.children("region").orEmpty().mapNotNull { region ->
                region.attr("id")?.let { it to regionOf(it, region) }
            }.toMap()

        private val out = ArrayList<SubtitleCue.Text>()
        private var paragraphs = 0
        private var lastEnd = 0L

        fun cues(): List<SubtitleCue.Text> {
            root.child("body")?.let { body -> walk(body, 0L, null, emptyList(), null) }
            // A visible background that shows with no text is a cue of its own for the region's time.
            for (def in regions.values) {
                val region = def.region ?: continue
                if (region.showBackground != CueShowBackground.Always || alphaOf(region.backgroundColor) == 0) continue
                val begin = def.begin ?: 0L
                val end = def.end ?: lastEnd
                if (end > begin) {
                    out += SubtitleCue.Text(begin, end, emptyList(), CueLayout(region = region, regionOrder = -1), def.layer)
                }
            }
            return out.sortedBy { it.startMicros }
        }

        /**
         * Times [element] inside a parent that began at [parentBegin] and ends at [parentEnd], with
         * [chain] the styles of its ancestors in order and [regionId] the region they named.
         */
        private fun walk(element: XmlElement, parentBegin: Long, parentEnd: Long?, chain: List<Effect>, regionId: String?): Long? {
            if (paragraphs >= maxCues) return null
            val begin = parentBegin + (element.attr("begin")?.let(clock::micros) ?: 0L)
            var end = element.attr("end")?.let { parentBegin + clock.micros(it) }
                ?: element.attr("dur")?.let { begin + clock.micros(it) }
                ?: parentEnd
            if (end != null && parentEnd != null) end = minOf(end, parentEnd)
            val ownChain = chain + effectOf(element)
            val ownRegion = element.attr("region") ?: regionId
            if (element.name == "p") {
                paragraph(element, begin, end, ownChain, ownRegion)
                return end
            }
            val sequence = element.attr("timeContainer") == "seq"
            var childBegin = begin
            for (child in element.children) {
                if (child.name !in TIMED) continue
                val childEnd = walk(child, if (sequence) childBegin else begin, end, ownChain, ownRegion)
                // In a sequence each child begins where the one before it ended.
                if (sequence) childBegin = childEnd ?: break
            }
            return end
        }

        private fun paragraph(element: XmlElement, begin: Long, end: Long?, chain: List<Effect>, regionId: String?) {
            paragraphs++
            val def = regionId?.let(regions::get)
            var look = Look.DEFAULT.with(def?.effect, space)
            for (effect in chain) look = look.with(effect, space)
            val runs = ArrayList<Run>()
            runs(element, look, ownBackground(chain.last()), preserve = element.attr("space") == "preserve", runs)
            val spans = spansOf(runs)
            if (end == null || end <= begin || spans.isEmpty()) return
            if (end > lastEnd) lastEnd = end
            val region = def?.region
            // TTML starts a line at its start edge, but a paragraph with no region and no alignment
            // of its own goes to the bottom like a SubRip line, and is centred like one.
            val horizontal = if (region == null && look.textAlign.isEmpty()) Horizontal.Center else look.horizontal()
            out += SubtitleCue.Text(
                startMicros = begin,
                endMicros = end,
                spans = spans,
                layout = CueLayout(
                    alignment = when (horizontal) {
                        Horizontal.Left -> if (region != null) CueAlignment.TopLeft else CueAlignment.BottomLeft
                        Horizontal.Right -> if (region != null) CueAlignment.TopRight else CueAlignment.BottomRight
                        Horizontal.Center -> if (region != null) CueAlignment.TopCenter else CueAlignment.BottomCenter
                    },
                    // A region's lines break as its author laid them out, filling each line first.
                    wrap = if (look.noWrap) CueWrap.Never else if (region != null) CueWrap.None else CueWrap.Balanced,
                    authoredHeight = if (runs.any { it.look.sizeFraction != null }) AUTHORED_HEIGHT else null,
                    region = region,
                    regionOrder = paragraphs - 1,
                ),
                layer = def?.layer ?: 0,
            )
        }

        /** The runs inside [element], in order, with each span in its own look and `br` as a line break. */
        private fun runs(element: XmlElement, look: Look, background: Int?, preserve: Boolean, out: MutableList<Run>) {
            for (node in element.content) {
                when (node) {
                    is XmlText -> {
                        val text = if (preserve) node.value else node.value.replace(WHITESPACE, " ")
                        if (text.isNotEmpty()) out += Run(text, look, background)
                    }
                    is XmlElement -> when (node.name) {
                        "br" -> out += Run("\n", look, background)
                        "span" -> {
                            val effect = effectOf(node)
                            runs(
                                node,
                                look.with(effect, space),
                                ownBackground(effect) ?: background,
                                preserve || node.attr("space") == "preserve",
                                out,
                            )
                        }
                        // Metadata, animation and anything else carries no text that is shown.
                        else -> Unit
                    }
                }
            }
        }

        /** What [element]'s named styles set, each after the styles that one names, then its own attributes. */
        private fun effectOf(element: XmlElement): Effect = styles.effectOf(element.attr("style")).then(Effect.of(element))

        private fun ownBackground(effect: Effect): Int? = effect.backgroundColor?.let(::colorOf)

        private fun regionOf(id: String, element: XmlElement): RegionDef {
            // Named styles, then styles nested in the region, then its own attributes (TTML 1, 8.4.4.2).
            var effect = styles.effectOf(element.attr("style"))
            for (nested in element.children("style")) effect = effect.then(styles.effectOf(nested.attr("style"))).then(Effect.of(nested))
            effect = effect.then(Effect.of(element))
            val begin = element.attr("begin")?.let(clock::micros)
            val end = element.attr("end")?.let(clock::micros) ?: element.attr("dur")?.let { (begin ?: 0L) + clock.micros(it) }
            return RegionDef(space.region(id, effect), effect, effect.zIndex?.trim()?.toIntOrNull() ?: 0, begin, end)
        }
    }

    /** A region of the document: its box, or null when it cannot be placed, and the styles it gives its text. */
    private class RegionDef(val region: CueRegion?, val effect: Effect, val layer: Int, val begin: Long?, val end: Long?)

    /** A run of text in one look; a line break when [text] is a newline. */
    private class Run(val text: String, val look: Look, val background: Int?)

    /**
     * [runs] as spans: each line with its spaces collapsed and trimmed, no blank line at either end,
     * and neighbouring runs of one style joined.
     */
    private fun spansOf(runs: List<Run>): List<StyledSpan> {
        // Split into lines, each a list of runs, at every newline.
        val lines = ArrayList<MutableList<Run>>()
        var line = ArrayList<Run>()
        for (run in runs) {
            val parts = run.text.split('\n')
            parts.forEachIndexed { index, part ->
                if (index > 0) {
                    lines += line
                    line = ArrayList()
                }
                if (part.isNotEmpty()) line += Run(part, run.look, run.background)
            }
        }
        lines += line
        val tidy = lines.map(::tidyLine)
        val first = tidy.indexOfFirst { it.isNotEmpty() }
        if (first < 0) return emptyList()
        val last = tidy.indexOfLast { it.isNotEmpty() }
        val spans = ArrayList<StyledSpan>()
        for (index in first..last) {
            val lineRuns = tidy[index]
            if (index > first) {
                // The break takes the style of the run before it, so it joins that span.
                val previous = spans.lastOrNull()
                if (previous != null) spans[spans.lastIndex] = previous.copy(text = previous.text + "\n") else spans += StyledSpan("\n")
            }
            for (run in lineRuns) {
                val style = run.look.styleWith(run.background)
                val previous = spans.lastOrNull()
                if (previous != null && previous.style == style) {
                    spans[spans.lastIndex] = previous.copy(text = previous.text + run.text)
                } else {
                    spans += StyledSpan(run.text, style)
                }
            }
        }
        return spans
    }

    /** One line's runs with each run of spaces made one, across runs, and the ends trimmed. */
    private fun tidyLine(runs: List<Run>): List<Run> {
        val collapsed = ArrayList<Run>(runs.size)
        var lastWasSpace = false
        for (run in runs) {
            val text = buildString {
                for (c in run.text) {
                    if (c == ' ' && lastWasSpace) continue
                    append(c)
                    lastWasSpace = c == ' '
                }
            }
            if (text.isNotEmpty()) collapsed += Run(text, run.look, run.background)
        }
        while (collapsed.isNotEmpty()) {
            val head = collapsed.first().let { Run(it.text.trimStart(), it.look, it.background) }
            if (head.text.isEmpty()) collapsed.removeAt(0) else { collapsed[0] = head; break }
        }
        while (collapsed.isNotEmpty()) {
            val tail = collapsed.last().let { Run(it.text.trimEnd(), it.look, it.background) }
            if (tail.text.isEmpty()) collapsed.removeAt(collapsed.lastIndex) else { collapsed[collapsed.lastIndex] = tail; break }
        }
        return collapsed
    }

    /**
     * The named styles of one document, each resolved once (#408).
     *
     * A style's [Effect] is what the styles it names set, each after the styles that one names, and
     * then its own attributes, so the TTML2 order of overrides holds. It is worked out the first
     * time anything names the style and kept for the rest of the document. A style that names one
     * still being worked out is a loop, which TTML2 (10.4.1.3) calls an error: the repeat adds
     * nothing. Following every chain to a fixed depth instead cost about n^9 visits for a style that
     * named itself n times, and repeated that for every element that named it.
     */
    private class Styles(private val byId: Map<String, XmlElement>, private val work: TtmlWork) {
        private val resolved = HashMap<String, Effect>()
        private val resolving = HashSet<String>()

        /** Names left to follow in this document. Past it, a name is ignored, so a hostile document still ends. */
        private var budget = MAX_STYLE_REFERENCES

        /** What the styles in [names] set, in their order. */
        fun effectOf(names: String?): Effect {
            if (names.isNullOrBlank()) return Effect.NONE
            var effect = Effect.NONE
            for (name in names.trim().split(WHITESPACE)) {
                if (budget <= 0) break
                budget--
                effect = effect.then(resolve(name))
            }
            return effect
        }

        private fun resolve(id: String): Effect {
            resolved[id]?.let { return it }
            if (id in resolving || resolving.size >= MAX_STYLE_CHAIN) return Effect.NONE
            val style = byId[id] ?: return Effect.NONE
            resolving += id
            work.styleVisits++
            val effect = effectOf(style.attr("style")).then(Effect.of(style))
            resolving -= id
            resolved[id] = effect
            return effect
        }
    }

    /** What one source of style sets, each property as written, or null where it says nothing. */
    private data class Effect(
        val fontStyle: String? = null,
        val fontWeight: String? = null,
        val textDecoration: String? = null,
        val color: String? = null,
        val backgroundColor: String? = null,
        val fontFamily: String? = null,
        val fontSize: String? = null,
        val textAlign: String? = null,
        val direction: String? = null,
        val textOutline: String? = null,
        val wrapOption: String? = null,
        val origin: String? = null,
        val extent: String? = null,
        val padding: String? = null,
        val displayAlign: String? = null,
        val showBackground: String? = null,
        val overflow: String? = null,
        val zIndex: String? = null,
        val writingMode: String? = null,
    ) {
        /** This, with what [later] sets put over it. */
        fun then(later: Effect): Effect = Effect(
            later.fontStyle ?: fontStyle,
            later.fontWeight ?: fontWeight,
            later.textDecoration?.let { mergeDecoration(textDecoration, it) } ?: textDecoration,
            later.color ?: color,
            later.backgroundColor ?: backgroundColor,
            later.fontFamily ?: fontFamily,
            later.fontSize ?: fontSize,
            later.textAlign ?: textAlign,
            later.direction ?: direction,
            later.textOutline ?: textOutline,
            later.wrapOption ?: wrapOption,
            later.origin ?: origin,
            later.extent ?: extent,
            later.padding ?: padding,
            later.displayAlign ?: displayAlign,
            later.showBackground ?: showBackground,
            later.overflow ?: overflow,
            later.zIndex ?: zIndex,
            later.writingMode ?: writingMode,
        )

        companion object {
            val NONE = Effect()

            /** What [source]'s own attributes set. */
            fun of(source: XmlElement): Effect = Effect(
                fontStyle = source.attr("fontStyle"),
                fontWeight = source.attr("fontWeight"),
                textDecoration = source.attr("textDecoration"),
                color = source.attr("color"),
                backgroundColor = source.attr("backgroundColor"),
                fontFamily = source.attr("fontFamily"),
                fontSize = source.attr("fontSize"),
                textAlign = source.attr("textAlign"),
                direction = source.attr("direction"),
                textOutline = source.attr("textOutline"),
                wrapOption = source.attr("wrapOption"),
                origin = source.attr("origin"),
                extent = source.attr("extent"),
                padding = source.attr("padding"),
                displayAlign = source.attr("displayAlign"),
                showBackground = source.attr("showBackground"),
                overflow = source.attr("overflow"),
                zIndex = source.attr("zIndex"),
                writingMode = source.attr("writingMode"),
            )

            /** Two decorations, the later one's words winning where both speak of one line. */
            private fun mergeDecoration(earlier: String?, later: String): String =
                if (earlier == null) later else "$earlier $later"
        }
    }

    private enum class Horizontal { Left, Center, Right }

    /** The computed look of a run of text: what it inherits, and its own. */
    private data class Look(
        val italic: Boolean,
        val bold: Boolean,
        val underline: Boolean,
        val lineThrough: Boolean,
        val color: Int,
        val fontFamily: String?,
        /** An absolute size, as a fraction of the root's height, or null for the player's own. */
        val sizeFraction: Float?,
        /** A factor on the player's own size, while no absolute size was given. */
        val sizeFactor: Float,
        val textAlign: String,
        val rightToLeft: Boolean,
        val outlineColor: Int?,
        val outlineNone: Boolean,
        val noWrap: Boolean,
    ) {
        /** This look under [effect]'s inherited properties. */
        fun with(effect: Effect?, space: Space): Look {
            if (effect == null || effect == Effect.NONE) return this
            var look = this
            effect.fontStyle?.let { look = look.copy(italic = it == "italic" || it == "oblique") }
            effect.fontWeight?.let { look = look.copy(bold = it == "bold") }
            effect.textDecoration?.let { decoration ->
                for (word in decoration.trim().split(WHITESPACE)) {
                    when (word) {
                        "underline" -> look = look.copy(underline = true)
                        "noUnderline" -> look = look.copy(underline = false)
                        "lineThrough" -> look = look.copy(lineThrough = true)
                        "noLineThrough" -> look = look.copy(lineThrough = false)
                        "none" -> look = look.copy(underline = false, lineThrough = false)
                    }
                }
            }
            effect.color?.let(::colorOf)?.let { look = look.copy(color = it) }
            effect.fontFamily?.let { look = look.copy(fontFamily = familyOf(it)) }
            effect.fontSize?.let { look = look.sized(it.trim(), space) }
            effect.textAlign?.let { look = look.copy(textAlign = it.trim()) }
            effect.direction?.let { look = look.copy(rightToLeft = it.trim() == "rtl") }
            effect.textOutline?.let { outline ->
                val words = outline.trim().split(WHITESPACE)
                look = if (words.firstOrNull() == "none") {
                    look.copy(outlineNone = true)
                } else {
                    look.copy(outlineNone = false, outlineColor = words.firstOrNull()?.let(::colorOf) ?: look.outlineColor)
                }
            }
            effect.wrapOption?.let { look = look.copy(noWrap = it.trim() == "noWrap") }
            return look
        }

        /** This look at the font size [value], whose last length is the height of the glyphs. */
        private fun sized(value: String, space: Space): Look {
            val length = value.split(WHITESPACE).lastOrNull() ?: return this
            val number = numberOf(length) ?: return this
            return when {
                length.endsWith("%") -> scaled(number / 100f)
                length.endsWith("em") -> scaled(number)
                length.endsWith("c") -> copy(sizeFraction = number / space.rows, sizeFactor = 1f)
                length.endsWith("px") -> space.height?.let { copy(sizeFraction = number / it, sizeFactor = 1f) } ?: this
                else -> this
            }
        }

        private fun scaled(factor: Float): Look =
            if (sizeFraction != null) copy(sizeFraction = sizeFraction * factor) else copy(sizeFactor = sizeFactor * factor)

        fun horizontal(): Horizontal = when (textAlign) {
            "left" -> Horizontal.Left
            "right" -> Horizontal.Right
            "center" -> Horizontal.Center
            "end" -> if (rightToLeft) Horizontal.Left else Horizontal.Right
            else -> if (rightToLeft) Horizontal.Right else Horizontal.Left
        }

        /** This look as a cue style, over [background]. */
        fun styleWith(background: Int?): CueStyle = CueStyle(
            fontFamily = fontFamily,
            fontSizePx = sizeFraction?.let { it * AUTHORED_HEIGHT },
            bold = bold,
            italic = italic,
            underline = underline,
            strikeThrough = lineThrough,
            primaryColor = color,
            outlineColor = outlineColor ?: DEFAULT_STYLE.outlineColor,
            outlineWidthPx = if (outlineNone) 0f else DEFAULT_STYLE.outlineWidthPx,
            backgroundColor = background ?: DEFAULT_STYLE.backgroundColor,
            relativeSize = if (sizeFraction == null) sizeFactor else 1f,
        )

        companion object {
            private val DEFAULT_STYLE = CueStyle()

            /** TTML's initial values, except that the text keeps the player's size and outline. */
            val DEFAULT = Look(
                italic = false,
                bold = false,
                underline = false,
                lineThrough = false,
                color = DEFAULT_STYLE.primaryColor,
                fontFamily = null,
                sizeFraction = null,
                sizeFactor = 1f,
                textAlign = "",
                rightToLeft = false,
                outlineColor = null,
                outlineNone = false,
                noWrap = false,
            )
        }
    }

    /** The document's root container: its size in pixels when it states one, and its cell grid. */
    private class Space(root: XmlElement) {
        val width: Float?
        val height: Float?
        val columns: Float
        val rows: Float

        init {
            val extent = root.attr("extent")?.trim()?.split(WHITESPACE)
            val pixels = extent?.takeIf { parts -> parts.size == 2 && parts.all { it.endsWith("px") } }?.map { numberOf(it) }
            width = pixels?.get(0)?.takeIf { it > 0f }
            height = pixels?.get(1)?.takeIf { it > 0f }
            val cells = root.attr("cellResolution")?.trim()?.split(WHITESPACE)?.map { it.toFloatOrNull() }
            columns = cells?.getOrNull(0)?.takeIf { it > 0f } ?: 32f
            rows = cells?.getOrNull(1)?.takeIf { it > 0f } ?: 15f
        }

        /** [length] across, as a fraction of the root's width, or null when it cannot be resolved. */
        fun across(length: String): Float? = fraction(length, width, columns)

        /** [length] down, as a fraction of the root's height, or null when it cannot be resolved. */
        fun down(length: String): Float? = fraction(length, height, rows)

        private fun fraction(length: String, pixels: Float?, cells: Float): Float? {
            val number = numberOf(length) ?: return null
            return when {
                length.endsWith("%") -> number / 100f
                length.endsWith("c") -> number / cells
                length.endsWith("px") -> pixels?.let { number / it }
                else -> null
            }
        }

        /** The region [id] that [effect] places, or null when its box cannot be resolved. */
        fun region(id: String, effect: Effect): CueRegion? {
            val origin = effect.origin?.trim()?.takeIf { it != "auto" }?.split(WHITESPACE)
            val left = origin?.let { across(it.getOrNull(0) ?: return null) ?: return null } ?: 0f
            val top = origin?.let { down(it.getOrNull(1) ?: return null) ?: return null } ?: 0f
            val extent = effect.extent?.trim()?.takeIf { it != "auto" }?.split(WHITESPACE)
            val width = extent?.let { across(it.getOrNull(0) ?: return null) ?: return null } ?: 1f
            val height = extent?.let { down(it.getOrNull(1) ?: return null) ?: return null } ?: 1f
            if (!(width > 0f && height > 0f)) return null
            val rightToLeft = effect.writingMode?.trim().let { it == "rl" || it == "rltb" }
            return CueRegion(
                id = id,
                left = left,
                top = top,
                width = width,
                height = height,
                padding = effect.padding?.let { padding(it, width, height, rightToLeft) } ?: CueInsets.None,
                displayAlign = when (effect.displayAlign?.trim()) {
                    "center" -> CueDisplayAlign.Center
                    "after" -> CueDisplayAlign.After
                    else -> CueDisplayAlign.Before
                },
                backgroundColor = effect.backgroundColor?.let(::colorOf) ?: 0,
                showBackground = if (effect.showBackground?.trim() == "whenActive") CueShowBackground.WhenActive else CueShowBackground.Always,
                clip = effect.overflow?.trim() != "visible",
            )
        }

        /**
         * TTML's padding of one to four lengths, before, end, after and start, as insets that are
         * fractions of the region's own [width] and [height].
         */
        private fun padding(value: String, width: Float, height: Float, rightToLeft: Boolean): CueInsets {
            val parts = value.trim().split(WHITESPACE)
            val (before, end, after, start) = when (parts.size) {
                1 -> listOf(parts[0], parts[0], parts[0], parts[0])
                2 -> listOf(parts[0], parts[1], parts[0], parts[1])
                3 -> listOf(parts[0], parts[1], parts[2], parts[1])
                else -> listOf(parts[0], parts[1], parts[2], parts[3])
            }
            fun acrossOf(length: String) = if (length.endsWith("%")) numberOf(length)?.div(100f) else across(length)?.div(width)
            fun downOf(length: String) = if (length.endsWith("%")) numberOf(length)?.div(100f) else down(length)?.div(height)
            val startInset = acrossOf(start) ?: 0f
            val endInset = acrossOf(end) ?: 0f
            return CueInsets(
                left = if (rightToLeft) endInset else startInset,
                top = downOf(before) ?: 0f,
                right = if (rightToLeft) startInset else endInset,
                bottom = downOf(after) ?: 0f,
            )
        }
    }

    /** The document's time base: its effective frame rate, sub-frame rate and tick rate (TTML 1, section 7.2). */
    private class Clock(root: XmlElement) {
        private val frameRate: Double
        private val subFrameRate: Double
        private val tickRate: Double

        init {
            val base = root.attr("frameRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 30.0
            val multiplier = root.attr("frameRateMultiplier")?.trim()?.split(WHITESPACE)?.let { parts ->
                val numerator = parts.getOrNull(0)?.toDoubleOrNull()
                val denominator = parts.getOrNull(1)?.toDoubleOrNull()
                if (numerator != null && denominator != null && numerator > 0 && denominator > 0) numerator / denominator else null
            } ?: 1.0
            frameRate = base * multiplier
            subFrameRate = root.attr("subFrameRate")?.toDoubleOrNull()?.takeIf { it > 0 } ?: 1.0
            tickRate = root.attr("tickRate")?.toDoubleOrNull()?.takeIf { it > 0 }
                ?: if (root.attr("frameRate") != null) frameRate * subFrameRate else 1.0
        }

        /** A time expression, in microseconds. */
        fun micros(expression: String): Long {
            val value = expression.trim()
            CLOCK_TIME.matchEntire(value)?.let { match ->
                val g = match.groupValues
                var seconds = g[1].toDouble() * 3600 + g[2].toDouble() * 60 + g[3].toDouble()
                if (g[4].isNotEmpty()) seconds += "0${g[4]}".toDouble()
                if (g[5].isNotEmpty()) seconds += g[5].toDouble() / frameRate
                if (g[6].isNotEmpty()) seconds += "0.${g[6]}".toDouble() / frameRate
                return (seconds * 1_000_000).roundToLong()
            }
            OFFSET_TIME.matchEntire(value)?.let { match ->
                val count = match.groupValues[1].toDouble()
                val seconds = when (match.groupValues[2]) {
                    "h" -> count * 3600
                    "m" -> count * 60
                    "s" -> count
                    "ms" -> count / 1000
                    "f" -> count / frameRate
                    else -> count / tickRate
                }
                return (seconds * 1_000_000).roundToLong()
            }
            throw IllegalArgumentException("not a TTML time expression: $expression")
        }
    }

    /** The number at the start of a length such as `12.5%` or `40px`. */
    private fun numberOf(length: String): Float? = NUMBER.find(length.trim())?.value?.toFloatOrNull()

    /** A family list's first named family; a generic family, such as `sansSerif`, is the player's own. */
    private fun familyOf(value: String): String? = value.split(',')
        .map { it.trim().trim('"', '\'') }
        .firstOrNull { it.isNotEmpty() && it !in GENERIC_FAMILIES }

    /**
     * A TTML colour as ARGB with a straight alpha: `#rrggbb`, `#rrggbbaa`, `rgb(r,g,b)`,
     * `rgba(r,g,b,a)` or one of TTML's named colours. Null for anything else.
     */
    private fun colorOf(value: String): Int? {
        val text = value.trim()
        if (text.startsWith("#")) {
            val hex = text.substring(1)
            val digits = hex.toLongOrNull(16) ?: return null
            return when (hex.length) {
                6 -> (0xFF000000L or digits).toInt()
                8 -> (((digits and 0xFF) shl 24) or (digits ushr 8)).toInt()
                else -> null
            }
        }
        val open = text.indexOf('(')
        if (open > 0 && text.endsWith(")")) {
            val function = text.substring(0, open).trim()
            val parts = text.substring(open + 1, text.length - 1).split(',').map { it.trim().toIntOrNull()?.coerceIn(0, 255) ?: return null }
            return when {
                function == "rgb" && parts.size == 3 -> (0xFF shl 24) or (parts[0] shl 16) or (parts[1] shl 8) or parts[2]
                function == "rgba" && parts.size == 4 -> (parts[3] shl 24) or (parts[0] shl 16) or (parts[1] shl 8) or parts[2]
                else -> null
            }
        }
        return NAMED_COLORS[text]
    }

    private fun alphaOf(argb: Int): Int = argb ushr 24

    /** The end of a declaration such as `<!DOCTYPE ...>` that starts at [at], past an internal subset. */
    private fun declarationEnd(text: String, at: Int, limit: Int): Int {
        var depth = 0
        var i = at
        while (i < limit) {
            when (text[i]) {
                '[' -> depth++
                ']' -> depth--
                '>' -> if (depth <= 0) return i + 1
            }
            i++
        }
        return -1
    }

    /** The value of the attribute [name] among [attributes], the part of a start tag after its name. */
    private fun attributeOf(attributes: String, name: String): String? {
        var i = 0
        while (i < attributes.length) {
            while (i < attributes.length && (attributes[i].isWhitespace() || attributes[i] == '/')) i++
            val nameStart = i
            while (i < attributes.length && !attributes[i].isWhitespace() && attributes[i] != '=') i++
            val found = attributes.substring(nameStart, i)
            while (i < attributes.length && attributes[i].isWhitespace()) i++
            if (i >= attributes.length || attributes[i] != '=') return null
            i++
            while (i < attributes.length && attributes[i].isWhitespace()) i++
            if (i >= attributes.length) return null
            val quote = attributes[i]
            if (quote != '"' && quote != '\'') return null
            val close = attributes.indexOf(quote, i + 1)
            if (close < 0) return null
            if (found == name) return attributes.substring(i + 1, close)
            i = close + 1
        }
        return null
    }

    /** The height that a TTML font size is written against: sizes are this times their fraction of the root. */
    private const val AUTHORED_HEIGHT = 10_000

    /** How much of a text is read to tell whether it is TTML. */
    private const val SNIFF_CHARS = 64 * 1024

    /**
     * How many style names one document may follow in all. Resolving each style once keeps an
     * ordinary document far below it; it bounds one whose style attributes name styles thousands of
     * times over.
     */
    private const val MAX_STYLE_REFERENCES = 100_000

    /**
     * How long a chain of styles naming styles is followed. Each link is a level of recursion, so a
     * hostile chain of thousands would overflow the stack; no real document comes near it.
     */
    private const val MAX_STYLE_CHAIN = 64

    private val NAMESPACES = setOf(
        "http://www.w3.org/ns/ttml",
        "http://www.w3.org/2006/10/ttaf1",
        "http://www.w3.org/2006/04/ttaf1",
    )

    /** The elements whose times nest: the rest of a body's content carries no time of its own. */
    private val TIMED = setOf("div", "p")

    private val GENERIC_FAMILIES = setOf(
        "default", "monospace", "sansSerif", "serif", "monospaceSansSerif", "monospaceSerif",
        "proportionalSansSerif", "proportionalSerif",
    )

    private val NAMED_COLORS: Map<String, Int> = mapOf(
        "transparent" to 0x00000000,
        "black" to 0xFF000000.toInt(),
        "silver" to 0xFFC0C0C0.toInt(),
        "gray" to 0xFF808080.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "maroon" to 0xFF800000.toInt(),
        "red" to 0xFFFF0000.toInt(),
        "purple" to 0xFF800080.toInt(),
        "fuchsia" to 0xFFFF00FF.toInt(),
        "magenta" to 0xFFFF00FF.toInt(),
        "green" to 0xFF008000.toInt(),
        "lime" to 0xFF00FF00.toInt(),
        "olive" to 0xFF808000.toInt(),
        "yellow" to 0xFFFFFF00.toInt(),
        "navy" to 0xFF000080.toInt(),
        "blue" to 0xFF0000FF.toInt(),
        "teal" to 0xFF008080.toInt(),
        "aqua" to 0xFF00FFFF.toInt(),
        "cyan" to 0xFF00FFFF.toInt(),
    )

    private val WHITESPACE = Regex("\\s+")
    private val NUMBER = Regex("""^[+-]?\d+(?:\.\d+)?""")
    private val CLOCK_TIME = Regex("""(\d+):(\d{2}):(\d{2})(?:(\.\d+)|:(\d+)(?:\.(\d+))?)?""")
    private val OFFSET_TIME = Regex("""(\d+(?:\.\d+)?)(h|ms|m|s|f|t)""")
}

/** What reading one TTML document cost, for a test to hold the cost to the document's size. */
internal class TtmlWork {
    /** How many named styles had their own attributes read. Each is read at most once per document. */
    var styleVisits: Int = 0
        internal set
}
