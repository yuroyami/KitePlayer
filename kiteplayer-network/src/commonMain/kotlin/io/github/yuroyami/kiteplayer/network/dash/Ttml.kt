package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.network.xml.XmlElement
import io.github.yuroyami.kiteplayer.network.xml.XmlMini
import io.github.yuroyami.kiteplayer.network.xml.XmlText
import kotlin.math.roundToLong

/** One subtitle cue: its text, with `<i>`, `<b>` and `<u>` and line breaks, from [startMicros] until [endMicros]. */
internal class TimedCue(val startMicros: Long, val endMicros: Long, val text: String)

/** What reading one TTML document cost, for a test to hold the cost to the document's size. */
internal class TtmlWork {
    /** How many named styles had their own attributes read. Each is read at most once per document. */
    var styleVisits: Int = 0
        internal set
}

/**
 * TTML documents as cues (#402), for the subtitle sets of a DASH presentation that FFmpeg cannot
 * read: a sidecar TTML file, and the TTML document in each sample of an `stpp` track. The cues
 * reach the player as WebVTT, which the HLS path already plays.
 *
 * What is kept is what a cue can carry through the player's packet path: the text, its line
 * breaks, and italic, bold and underline, from an element's own attributes or the styles it names.
 * Time expressions follow TTML 1, section 10.3.1: clock times with a fraction or frames, and offset
 * times in hours, minutes, seconds, milliseconds, frames or ticks, against the document's frame and
 * tick rates. An element's times count from its parent's begin and end with its parent, so a `div`
 * that begins at ten seconds moves every paragraph inside it.
 */
internal object Ttml {

    /** The cues of [xml], in time order, their times plus [offsetMicros]. */
    fun cues(xml: String, offsetMicros: Long = 0, work: TtmlWork = TtmlWork()): List<TimedCue> {
        val root = XmlMini.parse(xml, XmlMini.Limits(keepContent = true))
        require(root.name == "tt") { "not a TTML document: the root element is <${root.name}>" }
        val clock = Clock(root)
        val styles = Styles(
            root.child("head")?.child("styling")?.children("style").orEmpty()
                .mapNotNull { style -> style.attr("id")?.let { it to style } }.toMap(),
            work,
        )
        val out = ArrayList<TimedCue>()
        val body = root.child("body") ?: return out
        walk(body, 0L, null, Look.NONE, styles, clock, offsetMicros, out)
        return out.sortedBy { it.startMicros }
    }

    private fun walk(
        element: XmlElement,
        parentBegin: Long,
        parentEnd: Long?,
        inherited: Look,
        styles: Styles,
        clock: Clock,
        offsetMicros: Long,
        out: MutableList<TimedCue>,
    ) {
        val begin = parentBegin + (element.attr("begin")?.let(clock::micros) ?: 0L)
        var end = element.attr("end")?.let { parentBegin + clock.micros(it) }
            ?: element.attr("dur")?.let { begin + clock.micros(it) }
            ?: parentEnd
        if (end != null && parentEnd != null) end = minOf(end, parentEnd)
        val look = inherited.with(element, styles)
        if (element.name == "p") {
            val runs = ArrayList<Run>()
            runs(element, look, styles, preserve = element.attr("space") == "preserve", runs)
            val text = tidy(render(runs))
            if (end != null && end > begin && text.isNotEmpty()) {
                out += TimedCue(begin + offsetMicros, end + offsetMicros, text)
            }
            return
        }
        for (child in element.children) walk(child, begin, end, look, styles, clock, offsetMicros, out)
    }

    /** A run of text in one look, or a line break when [text] is a newline. */
    private class Run(val text: String, val look: Look)

    /** The runs inside [element], in order, with each span in its own look and `br` as a line break. */
    private fun runs(element: XmlElement, look: Look, styles: Styles, preserve: Boolean, out: MutableList<Run>) {
        for (node in element.content) {
            when (node) {
                is XmlText -> {
                    val text = if (preserve) node.value else node.value.replace(WHITESPACE, " ")
                    if (text.isNotEmpty()) out += Run(text, look)
                }
                is XmlElement -> when (node.name) {
                    "br" -> out += Run("\n", Look.NONE)
                    else -> runs(node, look.with(node, styles), styles, preserve || node.attr("space") == "preserve", out)
                }
            }
        }
    }

    /**
     * [runs] as WebVTT cue text: each run escaped and inside its own tags, because WebVTT cannot
     * turn italic off inside `<i>`. Neighbouring runs in the same look share their tags.
     */
    private fun render(runs: List<Run>): String = buildString {
        var at = 0
        while (at < runs.size) {
            val look = runs[at].look
            if (runs[at].text == "\n") {
                append('\n')
                at++
                continue
            }
            val text = StringBuilder()
            while (at < runs.size && runs[at].text != "\n" && runs[at].look == look) text.append(runs[at++].text)
            val tags = look.tags()
            tags.forEach { append('<').append(it).append('>') }
            append(escape(text.toString()))
            tags.asReversed().forEach { append("</").append(it).append('>') }
        }
    }

    /** Each line with its spaces collapsed and trimmed, with no blank line at either end. */
    private fun tidy(text: String): String =
        text.lines().map { it.replace(SPACES, " ").trim() }.dropWhile { it.isEmpty() }.dropLastWhile { it.isEmpty() }.joinToString("\n")

    private fun escape(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

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

    /** What one source of style sets: each of italic, bold and underline, or null where it says nothing. */
    private data class Effect(val italic: Boolean?, val bold: Boolean?, val underline: Boolean?) {
        /** This, with what [later] sets put over it. */
        fun then(later: Effect): Effect =
            Effect(later.italic ?: italic, later.bold ?: bold, later.underline ?: underline)

        companion object {
            val NONE = Effect(null, null, null)

            /** What [source]'s own attributes set. */
            fun of(source: XmlElement): Effect = Effect(
                italic = source.attr("fontStyle")?.let { it == "italic" || it == "oblique" },
                bold = source.attr("fontWeight")?.let { it == "bold" },
                underline = source.attr("textDecoration")?.let { decoration ->
                    if ("noUnderline" in decoration) false else if ("underline" in decoration) true else null
                },
            )
        }
    }

    /** Italic, bold and underline as TTML styles them. */
    private data class Look(val italic: Boolean, val bold: Boolean, val underline: Boolean) {
        /** This look under [element]: the styles it names, each after the styles that one names, then its own attributes. */
        fun with(element: XmlElement, styles: Styles): Look {
            val named = if (element.name != "style") styles.effectOf(element.attr("style")) else Effect.NONE
            val effect = named.then(Effect.of(element))
            return Look(effect.italic ?: italic, effect.bold ?: bold, effect.underline ?: underline)
        }

        fun tags(): List<String> = buildList {
            if (italic) add("i")
            if (bold) add("b")
            if (underline) add("u")
        }

        companion object {
            val NONE = Look(italic = false, bold = false, underline = false)
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

    private val WHITESPACE = Regex("\\s+")
    private val SPACES = Regex(" {2,}")
    private val CLOCK_TIME = Regex("""(\d+):(\d{2}):(\d{2})(?:(\.\d+)|:(\d+)(?:\.(\d+))?)?""")
    private val OFFSET_TIME = Regex("""(\d+(?:\.\d+)?)(h|ms|m|s|f|t)""")
}

/** [cues] as one WebVTT document, which FFmpeg's WebVTT reader takes as one subtitle segment. */
internal fun webVtt(cues: List<TimedCue>): String = buildString {
    append("WEBVTT\n\n")
    for (cue in cues) {
        append(vttTime(cue.startMicros)).append(" --> ").append(vttTime(cue.endMicros)).append('\n')
        append(cue.text).append("\n\n")
    }
}

/** Microseconds as a WebVTT timestamp, `hh:mm:ss.ttt`, never negative. */
private fun vttTime(micros: Long): String {
    val millis = micros.coerceAtLeast(0) / 1000
    val hours = millis / 3_600_000
    val minutes = millis / 60_000 % 60
    val seconds = millis / 1000 % 60
    val fraction = millis % 1000
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:" +
        "${seconds.toString().padStart(2, '0')}.${fraction.toString().padStart(3, '0')}"
}
