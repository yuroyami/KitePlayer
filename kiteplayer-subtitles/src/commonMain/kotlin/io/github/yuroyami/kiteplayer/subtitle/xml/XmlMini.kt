package io.github.yuroyami.kiteplayer.subtitle.xml

import io.github.yuroyami.kiteplayer.KitePlayerInternalApi

/**
 * The smallest XML reader that parses real DASH manifests and TTML subtitles: elements,
 * attributes, text, CDATA, comments and character entities, in pure commonMain with zero
 * dependencies, exactly as the Kotlin-first rule wants (libxml2 was refused as a dependency).
 * It lives here so that the subtitle readers and the network module share one bounded reader
 * (#492); the network module turns its refusals into its own public `XmlException`.
 *
 * Deliberately NOT a general XML processor: namespaces are handled by stripping prefixes
 * (DASH manifests are single-namespace in practice), DOCTYPE internal subsets are skipped
 * unread, and processing instructions are ignored. A malformed document throws
 * [XmlSyntaxException] with the UTF-16 character offset, never a silent partial tree.
 */
@KitePlayerInternalApi
public class XmlElement(
    public val name: String,
    public val attributes: Map<String, String>,
    public val children: List<XmlElement>,
    public val text: String,
    /**
     * The text and the elements inside this one, in document order, with text untrimmed. Empty
     * unless the parse asked for it with [XmlMini.Limits.keepContent]: TTML needs it, because a
     * span sits between two pieces of its paragraph's text, and a manifest never does.
     */
    public val content: List<XmlNode> = emptyList(),
) : XmlNode {
    public fun children(name: String): List<XmlElement> = children.filter { it.name == name }
    public fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }
    public fun attr(name: String): String? = attributes[name]
}

/** A piece of an element's content: a run of text, or an element. */
@KitePlayerInternalApi
public sealed interface XmlNode

/** A run of text inside an element, its entities decoded and its whitespace kept. */
@KitePlayerInternalApi
public class XmlText(public val value: String) : XmlNode {
    override fun toString(): String = "XmlText($value)"
}

/**
 * A document the reader refused: malformed, or past one of its limits. [reason] says what, and
 * [offset] is the UTF-16 character offset where the reader stopped.
 */
@KitePlayerInternalApi
public class XmlSyntaxException(public val reason: String, public val offset: Int) : Exception("$reason at offset $offset")

@KitePlayerInternalApi
public object XmlMini {

    /**
     * How deep a document may nest before the parser refuses.
     *
     * `parseElement` recurses once per level, so a manifest of ten thousand opening tags raised
     * `StackOverflowError`. That is an `Error`, not an `Exception`, so every `catch (Exception)`
     * in this module missed it and it came out of the player as a crash instead of a refusal.
     * Real DASH nests under ten levels; this ceiling is far above any honest document.
     */
    public const val MAX_DEPTH: Int = 256

    /**
     * The longest document the parser reads, in UTF-16 code units. The DASH door refuses a manifest
     * over 8 MiB before it gets here, and 8 MiB of UTF-8 never decodes to more code units than this.
     */
    public const val MAX_LENGTH: Int = 8 * 1024 * 1024

    /** How many attributes one element may carry. A DASH element carries a few dozen at most. */
    public const val MAX_ELEMENT_ATTRIBUTES: Int = 128

    /** How many attributes one document may carry in all. */
    public const val MAX_ATTRIBUTES: Int = 1024 * 1024

    /** How many elements one document may hold. */
    public const val MAX_ELEMENTS: Int = 256 * 1024

    /** The ceilings one parse runs under. The defaults are the constants above; tests pass smaller ones. */
    public class Limits(
        public val maxLength: Int = MAX_LENGTH,
        public val maxElementAttributes: Int = MAX_ELEMENT_ATTRIBUTES,
        public val maxAttributes: Int = MAX_ATTRIBUTES,
        public val maxElements: Int = MAX_ELEMENTS,
        /** Whether each element keeps its [XmlElement.content], which costs a list per element. */
        public val keepContent: Boolean = false,
    )

    /**
     * Parses one document and returns its root element. Every step moves forward through [text],
     * so the time a parse takes grows with the length of the document and nothing else. A document
     * past one of [limits] is refused with [XmlSyntaxException].
     */
    public fun parse(text: CharSequence, limits: Limits = Limits()): XmlElement {
        if (text.length > limits.maxLength) {
            throw XmlSyntaxException("the document is longer than ${limits.maxLength} characters", limits.maxLength)
        }
        val parser = Parser(text, limits)
        parser.skipProlog()
        val root = parser.parseElement()
        parser.skipMisc()
        return root
    }

    private class Parser(private val s: CharSequence, private val limits: Limits) {
        var at = 0
        private var depth = 0
        private var elementCount = 0
        private var attributeCount = 0

        fun skipProlog() {
            while (true) {
                skipWhitespace()
                when {
                    lookingAt("<?") -> skipUntil("?>")
                    lookingAt("<!--") -> skipUntil("-->")
                    lookingAt("<!DOCTYPE") -> skipDoctype()
                    else -> return
                }
            }
        }

        fun skipMisc() {
            while (at < s.length) {
                skipWhitespace()
                when {
                    at >= s.length -> return
                    lookingAt("<!--") -> skipUntil("-->")
                    lookingAt("<?") -> skipUntil("?>")
                    else -> return
                }
            }
        }

        fun parseElement(): XmlElement {
            if (++depth > MAX_DEPTH) {
                throw XmlSyntaxException("nested past $MAX_DEPTH elements, which no real manifest does", at)
            }
            if (++elementCount > limits.maxElements) {
                throw XmlSyntaxException("more than ${limits.maxElements} elements, which no real manifest has", at)
            }
            try {
                return parseElementBody()
            } finally {
                depth--
            }
        }

        private fun parseElementBody(): XmlElement {
            expect('<')
            val name = readName()
            val attributes = mutableMapOf<String, String>()
            var ownAttributes = 0
            while (true) {
                skipWhitespace()
                when {
                    lookingAt("/>") -> {
                        at += 2
                        return XmlElement(name, attributes, emptyList(), "")
                    }
                    peek() == '>' -> {
                        at++
                        break
                    }
                    else -> {
                        // Both counts come before the map sees the name, so one element's map
                        // never holds more than the limit on any target.
                        if (++ownAttributes > limits.maxElementAttributes) {
                            throw XmlSyntaxException("an element carries more than ${limits.maxElementAttributes} attributes", at)
                        }
                        if (++attributeCount > limits.maxAttributes) {
                            throw XmlSyntaxException("more than ${limits.maxAttributes} attributes in the document", at)
                        }
                        val attrName = readName()
                        skipWhitespace(); expect('='); skipWhitespace()
                        attributes[attrName] = readQuoted()
                    }
                }
            }
            val children = mutableListOf<XmlElement>()
            val textParts = StringBuilder()
            // Only a parse that asks for the order of text and elements pays for a list of it.
            val content = if (limits.keepContent) mutableListOf<XmlNode>() else null
            while (true) {
                when {
                    at >= s.length -> throw XmlSyntaxException("unclosed element <$name>", at)
                    lookingAt("</") -> {
                        at += 2
                        val closing = readName()
                        if (closing != name) throw XmlSyntaxException("</$closing> closes <$name>", at)
                        skipWhitespace(); expect('>')
                        return XmlElement(name, attributes, children, textParts.toString().trim(), content ?: emptyList())
                    }
                    lookingAt("<!--") -> skipUntil("-->")
                    lookingAt("<![CDATA[") -> {
                        at += 9
                        val end = s.indexOf("]]>", at)
                        if (end < 0) throw XmlSyntaxException("unterminated CDATA", at)
                        textParts.append(s, at, end)
                        content?.add(XmlText(s.substring(at, end)))
                        at = end + 3
                    }
                    lookingAt("<?") -> skipUntil("?>")
                    peek() == '<' -> {
                        val child = parseElement()
                        children += child
                        content?.add(child)
                    }
                    else -> {
                        val next = s.indexOf('<', at)
                        val end = if (next < 0) s.length else next
                        val text = decodeEntities(at, end)
                        textParts.append(text)
                        content?.add(XmlText(text))
                        at = end
                    }
                }
            }
        }

        /** A name with any namespace prefix stripped: `xsi:type` reads as `type`. */
        private fun readName(): String {
            val start = at
            while (at < s.length && !s[at].isWhitespace() && s[at] !in "=/><") at++
            if (at == start) throw XmlSyntaxException("expected a name", at)
            return s.substring(start, at).substringAfter(':')
        }

        private fun readQuoted(): String {
            val quote = peek()
            if (quote != '"' && quote != '\'') throw XmlSyntaxException("expected a quoted value", at)
            at++
            val end = s.indexOf(quote, at)
            if (end < 0) throw XmlSyntaxException("unterminated attribute value", at)
            val value = decodeEntities(at, end)
            at = end + 1
            return value
        }

        /**
         * The characters from [start] to [end] with their entity references decoded.
         *
         * XML 1.0, section 4.1, makes a reference an `&`, a name or `#` and digits, then a `;`. So
         * the search for the `;` stops at the first character that cannot be part of a reference.
         * That character is never an `&`, so each character is read at most twice and the decode
         * takes time in proportion to its length. A reference this reader does not know stays
         * literal.
         */
        private fun decodeEntities(start: Int, end: Int): String {
            var i = start
            while (i < end && s[i] != '&') i++
            if (i == end) return s.substring(start, end)
            val out = StringBuilder(end - start)
            out.append(s, start, i)
            while (i < end) {
                val c = s[i]
                if (c != '&') { out.append(c); i++; continue }
                var stop = i + 1
                while (stop < end && isReferenceChar(s[stop])) stop++
                val decoded = if (stop < end && s[stop] == ';') decodeReference(i + 1, stop) else null
                if (decoded == null) { out.append(c); i++ } else { out.append(decoded); i = stop + 1 }
            }
            return out.toString()
        }

        /** The characters of the references this reader decodes: ASCII letters, digits and `#`. */
        private fun isReferenceChar(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '#'

        /** The text of the reference named between [from] and [to], or null when it is not one this reader knows. */
        private fun decodeReference(from: Int, to: Int): String? = when {
            nameIs(from, to, "amp") -> "&"
            nameIs(from, to, "lt") -> "<"
            nameIs(from, to, "gt") -> ">"
            nameIs(from, to, "quot") -> "\""
            nameIs(from, to, "apos") -> "'"
            to - from > 2 && s[from] == '#' && (s[from + 1] == 'x' || s[from + 1] == 'X') ->
                codePointAt(from + 2, to, 16)?.let(::codePointToString)
            to - from > 1 && s[from] == '#' -> codePointAt(from + 1, to, 10)?.let(::codePointToString)
            else -> null
        }

        private fun nameIs(from: Int, to: Int, name: String): Boolean {
            if (to - from != name.length) return false
            for (k in name.indices) if (s[from + k] != name[k]) return false
            return true
        }

        /** The number written in [radix] between [from] and [to], or null past the last code point or on any other character. */
        private fun codePointAt(from: Int, to: Int, radix: Int): Int? {
            var value = 0
            for (k in from until to) {
                val digit = s[k].digitToIntOrNull(radix) ?: return null
                value = value * radix + digit
                if (value > 0x10FFFF) return null
            }
            return value
        }

        /**
         * One code point as a string, surrogate pairs included: `toChar()` used
         * to truncate everything above the basic plane to a wrong character. Surrogate and
         * out-of-range references answer null, which leaves the reference literal in the text.
         */
        private fun codePointToString(cp: Int): String? = when {
            cp < 0 || cp > 0x10FFFF || cp in 0xD800..0xDFFF -> null
            cp <= 0xFFFF -> cp.toChar().toString()
            else -> {
                val v = cp - 0x10000
                charArrayOf(
                    ((v shr 10) + 0xD800).toChar(),
                    ((v and 0x3FF) + 0xDC00).toChar(),
                ).concatToString()
            }
        }

        private fun skipDoctype() {
            // Balanced up to the closing '>', skipping an internal subset in brackets.
            var depth = 0
            while (at < s.length) {
                when (s[at]) {
                    '[' -> depth++
                    ']' -> depth--
                    '>' -> if (depth == 0) { at++; return }
                }
                at++
            }
            throw XmlSyntaxException("unterminated DOCTYPE", at)
        }

        private fun skipUntil(marker: String) {
            val end = s.indexOf(marker, at)
            if (end < 0) throw XmlSyntaxException("unterminated '$marker' block", at)
            at = end + marker.length
        }

        private fun skipWhitespace() {
            while (at < s.length && s[at].isWhitespace()) at++
        }

        private fun lookingAt(prefix: String): Boolean = s.startsWith(prefix, at)

        private fun peek(): Char =
            if (at < s.length) s[at] else throw XmlSyntaxException("unexpected end of document", at)

        private fun expect(c: Char) {
            if (at >= s.length || s[at] != c) throw XmlSyntaxException("expected '$c'", at)
            at++
        }
    }
}
