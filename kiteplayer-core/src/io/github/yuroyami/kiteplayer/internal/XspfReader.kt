@file:OptIn(io.github.yuroyami.kiteplayer.KitePlayerInternalApi::class)

package io.github.yuroyami.kiteplayer.internal

import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.uri.FileUri

/**
 * The tracks of an XSPF playlist, read as XML (#490).
 *
 * The first reader searched the text with patterns and a literal `</track>`, so a list written with
 * a namespace prefix read as no list, a closing tag inside CDATA or a comment cut its track short,
 * `xml:base` was ignored, and a location was taken as a path where the format makes it a URI
 * reference. This reads the markup as XML: comments, processing instructions, a DOCTYPE and CDATA
 * are what XML says they are, an element is known by its namespace and local name whatever its
 * prefix, and each location resolves against the base that applies to it.
 *
 * It is not a general XML processor and needs none: no entity is defined beyond the five XML
 * predefines and character references, and nothing is validated. A document that is not
 * well-formed, or deeper than [MAX_DEPTH], reads as no playlist.
 */
internal object XspfReader {

    /** The XSPF namespace. An element of no namespace counts too, as the lists other players write often omit it. */
    const val NAMESPACE: String = "http://xspf.org/ns/0/"

    private const val XML_NAMESPACE = "http://www.w3.org/XML/1998/namespace"
    private const val MAX_DEPTH = 64

    /** The items of [text], read against [base], the list's own address, or null when [text] is no XSPF playlist. */
    fun read(text: String, base: String): List<MediaItem>? {
        val root = runCatching { Parser(text).document() }.getOrNull() ?: return null
        if (!root.isXspf("playlist")) return null
        val documentBase = baseUriOf(base)
        val playlistBase = root.baseAgainst(documentBase)
        val items = mutableListOf<MediaItem>()
        for (list in root.children.filter { it.isXspf("trackList") }) {
            val listBase = list.baseAgainst(playlistBase)
            for (track in list.children.filter { it.isXspf("track") }) {
                val trackBase = track.baseAgainst(listBase)
                val location = track.children.firstOrNull { it.isXspf("location") && it.text().isNotBlank() } ?: continue
                items += MediaItem(
                    uri = address(location.baseAgainst(trackBase), location.text().trim()),
                    title = track.textOf("title"),
                    artist = track.textOf("creator"),
                    album = track.textOf("album"),
                )
            }
        }
        return items
    }

    /**
     * What [reference] names against [base]: a URI, or for a `file:` URI the path it names, its
     * escapes decoded. A location is a URI reference in XSPF, so `My%20Song.flac` beside a list on
     * disk is `My Song.flac`, as it is not in an M3U list.
     */
    private fun address(base: String, reference: String): String {
        val resolved = resolveUriReference(base, reference)
        return if (resolved.startsWith("file:", ignoreCase = true)) FileUri.localPath(resolved) ?: resolved else resolved
    }

    /**
     * [base] as a URI to resolve against: itself when it has a scheme, and for a path on disk the
     * `file:` URI of it, its `%` and spaces escaped so that a folder named with them survives.
     */
    private fun baseUriOf(base: String): String {
        val drive = base.length >= 3 && base[0].isLetter() && base[1] == ':' && (base[2] == '\\' || base[2] == '/')
        if (!drive && SCHEME_PREFIX.matchesAt(base, 0)) return base
        val path = base.replace('\\', '/').replace("%", "%25").replace(" ", "%20")
        return when {
            drive -> "file:///$path"
            path.startsWith("/") -> "file://$path"
            else -> path
        }
    }

    private val SCHEME_PREFIX = Regex("[A-Za-z][A-Za-z0-9+.-]+:")

    /** One element: its namespace and local name, its attributes by qualified name, and its content. */
    private class Element(
        val namespace: String?,
        val local: String,
        val attributes: Map<String, String>,
        val xmlBase: String?,
    ) {
        val children = mutableListOf<Element>()
        val content = StringBuilder()

        fun isXspf(name: String): Boolean = local == name && (namespace == NAMESPACE || namespace == null)

        /** The character data directly inside this element. */
        fun text(): String = content.toString()

        fun textOf(name: String): String? = children.firstOrNull { it.isXspf(name) }?.text()?.trim()?.ifEmpty { null }

        /** The base of this element's content: its `xml:base` resolved against [outer], or [outer]. */
        fun baseAgainst(outer: String): String = xmlBase?.trim()?.takeIf { it.isNotEmpty() }?.let { resolveUriReference(outer, it) } ?: outer
    }

    /** A strict reader of one document, which throws on anything that is not well-formed. */
    private class Parser(private val text: String) {
        private var at = 0

        fun document(): Element {
            var root: Element? = null
            if (text.startsWith("﻿")) at = 1
            while (true) {
                skipWhitespace()
                if (at >= text.length) break
                when {
                    text.startsWith("<?", at) -> skipPast("?>")
                    text.startsWith("<!--", at) -> skipPast("-->")
                    text.startsWith("<!DOCTYPE", at) -> skipDoctype()
                    text.startsWith("<", at) -> {
                        check(root == null) { "a second root element" }
                        root = element(emptyMap(), 0)
                    }
                    else -> error("text outside the root element")
                }
            }
            return checkNotNull(root) { "no root element" }
        }

        private fun element(outerScope: Map<String, String>, depth: Int): Element {
            check(depth < MAX_DEPTH) { "elements nest too deep" }
            expect('<')
            val name = readName()
            val raw = LinkedHashMap<String, String>()
            while (true) {
                skipWhitespace()
                check(at < text.length) { "the document ends inside a tag" }
                if (text[at] == '/' || text[at] == '>') break
                val attribute = readName()
                skipWhitespace()
                expect('=')
                skipWhitespace()
                val quote = text[at]
                check(quote == '"' || quote == '\'') { "an attribute value without quotes" }
                val end = text.indexOf(quote, at + 1)
                check(end > at) { "an attribute value that does not end" }
                raw[attribute] = decodeReferences(text.substring(at + 1, end))
                at = end + 1
            }
            val scope = HashMap(outerScope)
            for ((key, value) in raw) {
                if (key == "xmlns") scope[""] = value
                if (key.startsWith("xmlns:")) scope[key.removePrefix("xmlns:")] = value
            }
            val prefix = name.substringBefore(':', "")
            val local = name.substringAfter(':')
            val namespace = if (prefix == "xml") XML_NAMESPACE else scope[prefix]?.ifEmpty { null }
            check(prefix.isEmpty() || prefix == "xml" || prefix in scope) { "the prefix $prefix is not declared" }
            val element = Element(namespace, local, raw, raw["xml:base"])
            if (text[at] == '/') {
                at++
                expect('>')
                return element
            }
            expect('>')
            while (true) {
                check(at < text.length) { "the document ends inside <$name>" }
                when {
                    text.startsWith("</", at) -> {
                        at += 2
                        val closing = readName()
                        check(closing == name) { "</$closing> closes <$name>" }
                        skipWhitespace()
                        expect('>')
                        return element
                    }
                    text.startsWith("<!--", at) -> skipPast("-->")
                    text.startsWith("<![CDATA[", at) -> {
                        val end = text.indexOf("]]>", at + 9)
                        check(end >= 0) { "a CDATA section that does not end" }
                        element.content.append(text, at + 9, end)
                        at = end + 3
                    }
                    text.startsWith("<?", at) -> skipPast("?>")
                    text[at] == '<' -> element.children += element(scope, depth + 1)
                    else -> {
                        val next = text.indexOf('<', at).let { if (it < 0) text.length else it }
                        element.content.append(decodeReferences(text.substring(at, next)))
                        at = next
                    }
                }
            }
        }

        private fun readName(): String {
            val start = at
            while (at < text.length && !text[at].isWhitespace() && text[at] !in "/>=<\"'") at++
            check(at > start) { "a name was expected" }
            return text.substring(start, at)
        }

        private fun expect(char: Char) {
            check(at < text.length && text[at] == char) { "'$char' was expected" }
            at++
        }

        private fun skipWhitespace() {
            while (at < text.length && text[at].isWhitespace()) at++
        }

        private fun skipPast(end: String) {
            val found = text.indexOf(end, at)
            check(found >= 0) { "'$end' was expected" }
            at = found + end.length
        }

        /** Past a DOCTYPE, whose internal subset in brackets may hold `>`. */
        private fun skipDoctype() {
            var brackets = 0
            while (at < text.length) {
                when (text[at]) {
                    '[' -> brackets++
                    ']' -> brackets--
                    '>' -> if (brackets <= 0) {
                        at++
                        return
                    }
                }
                at++
            }
            error("a DOCTYPE that does not end")
        }
    }

    /** [raw] with its five predefined entities and its character references decoded. */
    private fun decodeReferences(raw: String): String {
        if ('&' !in raw) return raw
        val out = StringBuilder(raw.length)
        var at = 0
        while (at < raw.length) {
            val char = raw[at]
            if (char == '&') {
                val semicolon = raw.indexOf(';', at)
                val entity = if (semicolon > at) raw.substring(at + 1, semicolon) else ""
                val decoded = when {
                    entity == "amp" -> "&"
                    entity == "lt" -> "<"
                    entity == "gt" -> ">"
                    entity == "quot" -> "\""
                    entity == "apos" -> "'"
                    entity.startsWith("#x") || entity.startsWith("#X") -> entity.drop(2).toIntOrNull(16)?.let(::codePoint)
                    entity.startsWith("#") -> entity.drop(1).toIntOrNull()?.let(::codePoint)
                    else -> null
                }
                if (decoded != null) {
                    out.append(decoded)
                    at = semicolon + 1
                    continue
                }
            }
            out.append(char)
            at++
        }
        return out.toString()
    }

    private fun codePoint(value: Int): String? = when {
        value in 0..0xD7FF || value in 0xE000..0xFFFF -> value.toChar().toString()
        value in 0x10000..0x10FFFF -> {
            val offset = value - 0x10000
            charArrayOf((0xD800 + (offset shr 10)).toChar(), (0xDC00 + (offset and 0x3FF)).toChar()).concatToString()
        }
        else -> null
    }
}
