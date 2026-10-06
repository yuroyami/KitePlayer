package io.github.yuroyami.kiteplayer.subtitle

/**
 * WebVTT styling (#498): the colour classes every player knows, such as `<c.yellow>` and
 * `<c.bg_blue>`, and the `::cue` rules of a file's `STYLE` blocks, applied to the spans of a cue.
 *
 * A rule is read for what a cue here can carry: `color`, `background-color` (or a `background`
 * that is only a colour), `font-weight`, `font-style`, `text-decoration`, `font-family`, and
 * `font-size` as a percentage or in `em`, which scales the size the cue would have had. Its
 * selectors may be `::cue`, `::cue(#id)` for the cue of that identifier, and `::cue(...)` around
 * one element of the cue's text: a tag name, classes, and `[voice="name"]` on a `v`. A rule that
 * holds anything else, a selector or a property or a value, is ignored whole, never half applied,
 * as CSS ignores a rule it cannot read. Rules apply by specificity and then in file order, after
 * the standard's colour classes, which any rule of the file overrides.
 */
internal class VttStyleSheet private constructor(private val rules: List<Rule>) {

    /** The style of a cue's text before any of its tags: the `::cue` and `::cue(#id)` rules. */
    fun cueStyle(id: String?): CueStyle {
        var style = CueStyle()
        for (rule in rules) {
            if (rule.selector.matchesCue(id)) style = rule.declarations.applyTo(style)
        }
        return style
    }

    /** [style] inside an element [name] with [classes] and, for a `v`, [voice], of the cue [id]. */
    fun elementStyle(style: CueStyle, name: String, classes: List<String>, voice: String?, id: String?): CueStyle {
        var out = style
        for (cls in classes) STANDARD_CLASSES[cls]?.let { out = it.applyTo(out) }
        for (rule in rules) {
            if (rule.selector.matchesElement(name, classes, voice, id)) out = rule.declarations.applyTo(out)
        }
        return out
    }

    /** This sheet with the rules of [css], a `STYLE` block's text, after its own. */
    fun plus(css: CharSequence): VttStyleSheet {
        val added = parseRules(css, firstOrder = rules.size)
        if (added.isEmpty()) return this
        return VttStyleSheet((rules + added).sortedWith(compareBy<Rule> { it.selector.specificity }.thenBy { it.order }))
    }

    /** One selector of a rule, with the declarations it carries and its place in the file. */
    private class Rule(val selector: Selector, val declarations: Declarations, val order: Int)

    /**
     * What `::cue(...)` holds: a [tag] or none, [classes], a [voice] for `[voice=...]`, an [id], or
     * nothing at all, which is the bare `::cue`.
     */
    private class Selector(val tag: String?, val classes: List<String>, val voice: String?, val id: String?) {
        private val element = tag != null || classes.isNotEmpty() || voice != null

        /** CSS specificity folded into one number: ids, then classes and attributes, then tags. */
        val specificity: Int = (if (id != null) 10_000 else 0) + (classes.size + (if (voice != null) 1 else 0)) * 100 + (if (tag != null) 1 else 0)

        fun matchesCue(cueId: String?): Boolean = !element && (id == null || id == cueId)

        fun matchesElement(name: String, elementClasses: List<String>, elementVoice: String?, cueId: String?): Boolean {
            if (!element) return false
            if (id != null && id != cueId) return false
            if (tag != null && tag != name) return false
            if (voice != null && (name != "v" || voice != elementVoice)) return false
            return classes.all { it in elementClasses }
        }
    }

    /** The properties a rule sets, each null when it sets nothing. */
    class Declarations(
        val color: Int? = null,
        val background: Int? = null,
        val bold: Boolean? = null,
        val italic: Boolean? = null,
        val underline: Boolean? = null,
        val strikeThrough: Boolean? = null,
        val family: String? = null,
        val size: Float? = null,
    ) {
        fun applyTo(style: CueStyle): CueStyle = style.copy(
            primaryColor = color ?: style.primaryColor,
            backgroundColor = background ?: style.backgroundColor,
            bold = bold ?: style.bold,
            italic = italic ?: style.italic,
            underline = underline ?: style.underline,
            strikeThrough = strikeThrough ?: style.strikeThrough,
            fontFamily = family ?: style.fontFamily,
            relativeSize = size?.let { style.relativeSize * it } ?: style.relativeSize,
        )
    }

    companion object {
        val EMPTY: VttStyleSheet = VttStyleSheet(emptyList())

        /** The longest stylesheet read, in characters. A file's real ones are a few hundred. */
        private const val MAX_CSS_CHARS = 64 * 1024

        /** The colour classes of the WebVTT standard, section 6.4, as text and as background. */
        private val STANDARD_CLASSES: Map<String, Declarations> = buildMap {
            val colours = mapOf(
                "white" to 0xFFFFFFFF.toInt(), "lime" to 0xFF00FF00.toInt(), "cyan" to 0xFF00FFFF.toInt(),
                "red" to 0xFFFF0000.toInt(), "yellow" to 0xFFFFFF00.toInt(), "magenta" to 0xFFFF00FF.toInt(),
                "blue" to 0xFF0000FF.toInt(), "black" to 0xFF000000.toInt(),
            )
            for ((name, argb) in colours) {
                put(name, Declarations(color = argb))
                put("bg_$name", Declarations(background = argb))
            }
        }

        private fun parseRules(css: CharSequence, firstOrder: Int): List<Rule> {
            val text = stripComments(if (css.length > MAX_CSS_CHARS) css.subSequence(0, MAX_CSS_CHARS) else css)
            val out = mutableListOf<Rule>()
            var at = 0
            var order = firstOrder
            while (at < text.length) {
                val open = text.indexOf('{', at)
                if (open < 0) break
                val close = blockEnd(text, open) ?: break
                val prelude = text.substring(at, open).trim()
                // An at-rule, or any rule whose block holds another block, is skipped whole.
                if (!prelude.startsWith("@") && text.indexOf('{', open + 1).let { it < 0 || it > close }) {
                    val selectors = selectorList(prelude)
                    val declarations = declarations(text.substring(open + 1, close))
                    if (selectors != null && declarations != null) {
                        for (selector in selectors) out += Rule(selector, declarations, order++)
                    }
                }
                at = close + 1
            }
            return out
        }

        /** Where the block opened at [open] closes, counting the blocks inside it, or null when it never does. */
        private fun blockEnd(text: String, open: Int): Int? {
            var depth = 0
            for (i in open until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return i
                }
            }
            return null
        }

        private fun stripComments(css: CharSequence): String {
            val out = StringBuilder(css.length)
            var i = 0
            while (i < css.length) {
                if (css[i] == '/' && i + 1 < css.length && css[i + 1] == '*') {
                    val end = css.indexOf("*/", i + 2)
                    if (end < 0) break
                    i = end + 2
                    continue
                }
                out.append(css[i])
                i++
            }
            return out.toString()
        }

        /** The selectors of a rule's [prelude], split at its commas, or null when any one is not one this reads. */
        private fun selectorList(prelude: String): List<Selector>? {
            val parts = splitOutside(prelude, ',') ?: return null
            if (parts.isEmpty()) return null
            return parts.map { selector(it.trim()) ?: return null }
        }

        private fun selector(text: String): Selector? {
            if (text == "::cue") return Selector(null, emptyList(), null, null)
            if (!text.startsWith("::cue(") || !text.endsWith(")")) return null
            val inner = text.substring(6, text.length - 1).trim()
            if (inner.isEmpty()) return null
            if (inner == "*") return Selector(null, emptyList(), null, null)
            var at = 0
            val tag = NAME.matchAt(inner, 0)?.value?.also { at = it.length }
            val classes = mutableListOf<String>()
            var voice: String? = null
            var id: String? = null
            while (at < inner.length) {
                when (inner[at]) {
                    '.' -> {
                        val name = NAME.matchAt(inner, at + 1)?.value ?: return null
                        classes += name
                        at += 1 + name.length
                    }
                    '#' -> {
                        val name = ID.matchAt(inner, at + 1)?.value ?: return null
                        id = name
                        at += 1 + name.length
                    }
                    '[' -> {
                        val match = VOICE.matchAt(inner, at) ?: return null
                        voice = match.groupValues[1].ifEmpty { match.groupValues[2] }.ifEmpty { match.groupValues[3] }
                        at += match.value.length
                    }
                    // A combinator, a pseudo-class such as :past, or anything else.
                    else -> return null
                }
            }
            if (id != null && (tag != null || classes.isNotEmpty() || voice != null)) return null
            return Selector(tag, classes, voice, id)
        }

        /** The declarations of a rule's block, or null when any one is not one this can carry. */
        private fun declarations(block: String): Declarations? {
            var color: Int? = null
            var background: Int? = null
            var bold: Boolean? = null
            var italic: Boolean? = null
            var underline: Boolean? = null
            var strike: Boolean? = null
            var family: String? = null
            var size: Float? = null
            for (raw in splitOutside(block, ';') ?: return null) {
                val declaration = raw.trim()
                if (declaration.isEmpty()) continue
                val colon = declaration.indexOf(':')
                if (colon <= 0) return null
                val property = declaration.substring(0, colon).trim().lowercase()
                val value = declaration.substring(colon + 1).trim().removeSuffix("!important").trim()
                when (property) {
                    "color" -> color = cssColor(value) ?: return null
                    "background-color", "background" -> background = cssColor(value) ?: return null
                    "font-weight" -> bold = when (value.lowercase()) {
                        "bold", "bolder" -> true
                        "normal", "lighter" -> false
                        else -> (value.toIntOrNull() ?: return null) >= 600
                    }
                    "font-style" -> italic = when (value.lowercase()) {
                        "italic", "oblique" -> true
                        "normal" -> false
                        else -> return null
                    }
                    "text-decoration", "text-decoration-line" -> {
                        val words = value.lowercase().split(' ').filter { it.isNotEmpty() }
                        if (words.isEmpty() || words.any { it !in DECORATIONS }) return null
                        underline = "underline" in words
                        strike = "line-through" in words
                    }
                    "font-family" -> family = value.substringBefore(',').trim().trim('"', '\'').ifEmpty { return null }
                    "font-size" -> size = relativeSize(value) ?: return null
                    else -> return null
                }
            }
            return Declarations(color, background, bold, italic, underline, strike, family, size)
        }

        private val DECORATIONS = setOf("underline", "line-through", "none")

        /** A `font-size` of a percentage or of `em` as a factor, or null for any other. */
        private fun relativeSize(value: String): Float? {
            val v = value.lowercase()
            val factor = when {
                v.endsWith("%") -> v.dropLast(1).trim().toFloatOrNull()?.div(100f)
                v.endsWith("em") && !v.endsWith("rem") -> v.dropLast(2).trim().toFloatOrNull()
                else -> null
            }
            return factor?.takeIf { it.isFinite() && it > 0f && it <= 10f }
        }

        /** [text] split at each [separator] outside quotes, brackets and parentheses, or null when a quote never closes. */
        private fun splitOutside(text: String, separator: Char): List<String>? {
            val parts = mutableListOf<String>()
            var quote: Char? = null
            var depth = 0
            var start = 0
            for (i in text.indices) {
                val c = text[i]
                when {
                    quote != null -> if (c == quote) quote = null
                    c == '"' || c == '\'' -> quote = c
                    c == '(' || c == '[' -> depth++
                    c == ')' || c == ']' -> depth--
                    c == separator && depth == 0 -> {
                        parts += text.substring(start, i)
                        start = i + 1
                    }
                }
            }
            if (quote != null) return null
            parts += text.substring(start)
            return parts
        }

        /** A CSS colour as ARGB: a name, `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()` or `rgba()`. */
        fun cssColor(value: String): Int? {
            val v = value.trim().lowercase()
            if (v.startsWith("#")) {
                val hex = v.drop(1)
                if (hex.any { it !in HEX_DIGITS }) return null
                fun digit(i: Int) = hex[i].digitToInt(16)
                return when (hex.length) {
                    3, 4 -> {
                        val a = if (hex.length == 4) digit(3) * 17 else 255
                        argb(a, digit(0) * 17, digit(1) * 17, digit(2) * 17)
                    }
                    6, 8 -> {
                        val rgb = hex.substring(0, 6).toInt(16)
                        val a = if (hex.length == 8) hex.substring(6, 8).toInt(16) else 255
                        (a shl 24) or rgb
                    }
                    else -> null
                }
            }
            if (v.startsWith("rgb(") || v.startsWith("rgba(")) {
                if (!v.endsWith(")")) return null
                val inside = v.substringAfter('(').dropLast(1)
                val parts = inside.replace("/", " ").replace(",", " ").split(' ').filter { it.isNotEmpty() }
                if (parts.size != 3 && parts.size != 4) return null
                fun channel(part: String): Int? =
                    if (part.endsWith("%")) part.dropLast(1).toFloatOrNull()?.let { (it * 2.55f + 0.5f).toInt() } else part.toFloatOrNull()?.toInt()
                val r = channel(parts[0]) ?: return null
                val g = channel(parts[1]) ?: return null
                val b = channel(parts[2]) ?: return null
                val a = parts.getOrNull(3)?.let { part ->
                    if (part.endsWith("%")) part.dropLast(1).toFloatOrNull()?.div(100f) else part.toFloatOrNull()
                }?.let { (it.coerceIn(0f, 1f) * 255f + 0.5f).toInt() } ?: if (parts.size == 4) return null else 255
                return argb(a, r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
            }
            return NAMED_COLOURS[v]
        }

        private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

        private const val HEX_DIGITS = "0123456789abcdef"
        private val NAME = Regex("""[A-Za-z_][A-Za-z0-9_-]*""")
        private val ID = Regex("""[^\s.#\[\]()]+""")
        // The closing bracket is escaped, because Kotlin/JS compiles every pattern in JavaScript's
        // unicode mode, which refuses a bare one (#537).
        private val VOICE = Regex("""\[\s*voice\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\]\s]+))\s*\]""")

        /** The CSS colour names subtitles use: the sixteen of HTML, and a few more. */
        private val NAMED_COLOURS = mapOf(
            "black" to 0xFF000000.toInt(), "silver" to 0xFFC0C0C0.toInt(), "gray" to 0xFF808080.toInt(),
            "grey" to 0xFF808080.toInt(), "white" to 0xFFFFFFFF.toInt(), "maroon" to 0xFF800000.toInt(),
            "red" to 0xFFFF0000.toInt(), "purple" to 0xFF800080.toInt(), "fuchsia" to 0xFFFF00FF.toInt(),
            "magenta" to 0xFFFF00FF.toInt(), "green" to 0xFF008000.toInt(), "lime" to 0xFF00FF00.toInt(),
            "olive" to 0xFF808000.toInt(), "yellow" to 0xFFFFFF00.toInt(), "navy" to 0xFF000080.toInt(),
            "blue" to 0xFF0000FF.toInt(), "teal" to 0xFF008080.toInt(), "aqua" to 0xFF00FFFF.toInt(),
            "cyan" to 0xFF00FFFF.toInt(), "orange" to 0xFFFFA500.toInt(), "pink" to 0xFFFFC0CB.toInt(),
            "gold" to 0xFFFFD700.toInt(), "transparent" to 0x00000000,
        )
    }
}

/**
 * The spans of a WebVTT cue's text: `<b>`, `<i>`, `<u>`, classes on `<c>` and every other tag,
 * voices on `<v>`, language spans, and ruby, which keeps its reading after the base text in
 * parentheses because no text renderer here can draw it above the line (#511). Karaoke timestamps
 * are dropped. `<s>` and `<font color>`, which WebVTT converted from SubRip often keeps, still
 * strike and colour. Any other tag stays text. Each span's style comes from [sheet] for the cue
 * [id] (#498).
 *
 * Each character is read a bounded number of times: a tag ends at the first `>` after its `<`,
 * and the search for that `>` only moves forward and stops for good once it finds none.
 */
internal fun vttSpans(body: CharSequence, sheet: VttStyleSheet, id: String?): List<StyledSpan> {
    val base = sheet.cueStyle(id)
    val spans = mutableListOf<StyledSpan>()
    val buffer = StringBuilder()
    var style = base

    class Open(val name: String, val outer: CueStyle)
    val stack = ArrayDeque<Open>()
    var rubies = 0
    var reading = false

    fun flush() {
        if (buffer.isNotEmpty()) {
            spans += StyledSpan(buffer.toString(), style)
            buffer.clear()
        }
    }

    /** Closes the innermost open element called [name] and everything inside it. */
    fun close(name: String) {
        val at = stack.indexOfLast { it.name == name }
        if (at < 0) return
        while (stack.size > at) {
            val open = stack.removeLast()
            if (open.name == "rt" && reading) {
                buffer.append(')')
                reading = false
            }
            if (open.name == "ruby") rubies--
            flush()
            style = open.outer
        }
    }

    var close = body.indexOf('>')
    var i = 0
    while (i < body.length) {
        val c = body[i]
        if (c != '<' || close < 0) {
            buffer.append(c)
            i++
            continue
        }
        if (close <= i) close = body.indexOf('>', i + 1)
        if (close < 0) {
            buffer.append(body, i, body.length)
            break
        }
        val tag = if (close - i - 1 <= MAX_TAG_CHARS) VttTag.of(body, i + 1, close) else null
        when {
            tag == null -> buffer.append(body, i, close + 1)
            tag.timestamp -> Unit
            tag.end -> close(tag.name)
            else -> {
                if (tag.name == "rt" && (rubies == 0 || reading)) {
                    // A reading outside a ruby keeps its text without its tag, as the specification's parser does.
                } else {
                    val outer = style
                    var next = when (tag.name) {
                        "b" -> outer.copy(bold = true)
                        "i" -> outer.copy(italic = true)
                        "u" -> outer.copy(underline = true)
                        "s" -> outer.copy(strikeThrough = true)
                        "font" -> tag.fontColor?.let { outer.copy(primaryColor = it) } ?: outer
                        else -> outer
                    }
                    next = sheet.elementStyle(next, tag.name, tag.classes, tag.voice, id)
                    flush()
                    stack.addLast(Open(tag.name, outer))
                    style = next
                    if (tag.name == "ruby") rubies++
                    if (tag.name == "rt") {
                        buffer.append('(')
                        reading = true
                    }
                }
            }
        }
        i = close + 1
    }
    if (reading) buffer.append(')')
    flush()
    return spans.decodeSpanEntities()
}

/** The longest tag read as one, in characters between its brackets. Anything longer stays text. */
private const val MAX_TAG_CHARS = 512

/** One WebVTT tag, read from between its brackets. */
private class VttTag(
    val name: String,
    val end: Boolean,
    val classes: List<String>,
    val voice: String?,
    val fontColor: Int?,
    val timestamp: Boolean,
) {
    companion object {
        private val KNOWN = setOf("b", "i", "u", "c", "v", "lang", "ruby", "rt", "s", "font")
        private val KARAOKE = Regex("""\d{1,3}:?\d{1,2}:\d{1,2}\.\d{1,3}""")
        private val FONT_COLOR = Regex("""color\s*=\s*["']?([^"'>\s]+)""", RegexOption.IGNORE_CASE)

        /** The tag between [from] and [to] of [text], or null when it is not one this reads. */
        fun of(text: CharSequence, from: Int, to: Int): VttTag? {
            val inside = text.substring(from, to)
            if (KARAOKE.matches(inside)) return VttTag("", false, emptyList(), null, null, timestamp = true)
            val end = inside.startsWith('/')
            val body = if (end) inside.drop(1).trim() else inside
            val head = body.substringBefore(' ').substringBefore('\t').substringBefore('\n')
            val name = head.substringBefore('.').lowercase()
            if (name !in KNOWN) return null
            if (end) return VttTag(name, true, emptyList(), null, null, timestamp = false)
            val classes = head.split('.').drop(1).filter { it.isNotEmpty() }
            val annotation = body.substring(head.length).trim().ifEmpty { null }
            return VttTag(
                name = name,
                end = false,
                classes = classes,
                voice = annotation?.takeIf { name == "v" },
                fontColor = if (name == "font") {
                    FONT_COLOR.find(inside)?.groupValues?.get(1)?.let { VttStyleSheet.cssColor(it) }
                } else {
                    null
                },
                timestamp = false,
            )
        }
    }
}
