package io.github.yuroyami.kiteplayer.network.xml

/**
 * The smallest XML reader that parses real DASH manifests (KPKMP 17.12, the un-parked D-4
 * work): elements, attributes, text, CDATA, comments and character entities, in pure
 * commonMain with zero dependencies, exactly as decision D-7's Kotlin-first rule wants
 * (libxml2's verdict was NEVER).
 *
 * Deliberately NOT a general XML processor: namespaces are handled by stripping prefixes
 * (DASH manifests are single-namespace in practice), DOCTYPE internal subsets are skipped
 * unread, and processing instructions are ignored. A malformed document throws
 * [XmlException] with the byte offset, never a silent partial tree.
 */
public class XmlElement(
    public val name: String,
    public val attributes: Map<String, String>,
    public val children: List<XmlElement>,
    public val text: String,
) {
    public fun children(name: String): List<XmlElement> = children.filter { it.name == name }
    public fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }
    public fun attr(name: String): String? = attributes[name]
}

public class XmlException(message: String, public val offset: Int) : Exception("$message at offset $offset")

public object XmlMini {

    /** Parses one document and returns its root element. */
    public fun parse(text: String): XmlElement {
        val parser = Parser(text)
        parser.skipProlog()
        val root = parser.parseElement()
        parser.skipMisc()
        return root
    }

    private class Parser(private val s: String) {
        var at = 0

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
            expect('<')
            val name = readName()
            val attributes = mutableMapOf<String, String>()
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
                        val attrName = readName()
                        skipWhitespace(); expect('='); skipWhitespace()
                        attributes[attrName] = readQuoted()
                    }
                }
            }
            val children = mutableListOf<XmlElement>()
            val textParts = StringBuilder()
            while (true) {
                when {
                    at >= s.length -> throw XmlException("unclosed element <$name>", at)
                    lookingAt("</") -> {
                        at += 2
                        val closing = readName()
                        if (closing != name) throw XmlException("</$closing> closes <$name>", at)
                        skipWhitespace(); expect('>')
                        return XmlElement(name, attributes, children, textParts.toString().trim())
                    }
                    lookingAt("<!--") -> skipUntil("-->")
                    lookingAt("<![CDATA[") -> {
                        at += 9
                        val end = s.indexOf("]]>", at)
                        if (end < 0) throw XmlException("unterminated CDATA", at)
                        textParts.append(s, at, end)
                        at = end + 3
                    }
                    lookingAt("<?") -> skipUntil("?>")
                    peek() == '<' -> children += parseElement()
                    else -> {
                        val next = s.indexOf('<', at)
                        val end = if (next < 0) s.length else next
                        textParts.append(decodeEntities(s.substring(at, end)))
                        at = end
                    }
                }
            }
        }

        /** A name with any namespace prefix stripped: `xsi:type` reads as `type`. */
        private fun readName(): String {
            val start = at
            while (at < s.length && !s[at].isWhitespace() && s[at] !in "=/><") at++
            if (at == start) throw XmlException("expected a name", at)
            return s.substring(start, at).substringAfter(':')
        }

        private fun readQuoted(): String {
            val quote = peek()
            if (quote != '"' && quote != '\'') throw XmlException("expected a quoted value", at)
            at++
            val end = s.indexOf(quote, at)
            if (end < 0) throw XmlException("unterminated attribute value", at)
            val raw = s.substring(at, end)
            at = end + 1
            return decodeEntities(raw)
        }

        private fun decodeEntities(raw: String): String {
            if ('&' !in raw) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c != '&') { out.append(c); i++; continue }
                val semi = raw.indexOf(';', i + 1)
                if (semi < 0) { out.append(c); i++; continue }
                val entity = raw.substring(i + 1, semi)
                val decoded = when {
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.drop(2).toIntOrNull(16)?.let { it.toChar().toString() }
                    entity.startsWith("#") ->
                        entity.drop(1).toIntOrNull()?.let { it.toChar().toString() }
                    else -> null
                }
                if (decoded == null) { out.append(c); i++ } else { out.append(decoded); i = semi + 1 }
            }
            return out.toString()
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
            throw XmlException("unterminated DOCTYPE", at)
        }

        private fun skipUntil(marker: String) {
            val end = s.indexOf(marker, at)
            if (end < 0) throw XmlException("unterminated '$marker' block", at)
            at = end + marker.length
        }

        private fun skipWhitespace() {
            while (at < s.length && s[at].isWhitespace()) at++
        }

        private fun lookingAt(prefix: String): Boolean = s.startsWith(prefix, at)

        private fun peek(): Char =
            if (at < s.length) s[at] else throw XmlException("unexpected end of document", at)

        private fun expect(c: Char) {
            if (at >= s.length || s[at] != c) throw XmlException("expected '$c'", at)
            at++
        }
    }
}
