package io.github.yuroyami.kiteplayer.network.xml

import io.github.yuroyami.kiteplayer.subtitle.xml.XmlSyntaxException

/**
 * Malformed XML in a DASH manifest. `DashManifestParser.parse` throws it, with the UTF-16 character
 * [offset] where the parser stopped.
 */
public class XmlException(message: String, public val offset: Int) : Exception("$message at offset $offset")

/** An element of a parsed document, from the bounded XML reader the subtitle module shares (#492). */
internal typealias XmlElement = io.github.yuroyami.kiteplayer.subtitle.xml.XmlElement

/** A piece of an element's content: a run of text, or an element. */
internal typealias XmlNode = io.github.yuroyami.kiteplayer.subtitle.xml.XmlNode

/** A run of text inside an element. */
internal typealias XmlText = io.github.yuroyami.kiteplayer.subtitle.xml.XmlText

/** The ceilings one parse runs under. */
internal typealias XmlLimits = io.github.yuroyami.kiteplayer.subtitle.xml.XmlMini.Limits

/**
 * The bounded XML reader of `kiteplayer-subtitles`, which this module used to hold, with each of its
 * refusals thrown as this module's public [XmlException], with the same message and offset (#492).
 */
internal object XmlMini {
    const val MAX_DEPTH: Int = io.github.yuroyami.kiteplayer.subtitle.xml.XmlMini.MAX_DEPTH
    const val MAX_LENGTH: Int = io.github.yuroyami.kiteplayer.subtitle.xml.XmlMini.MAX_LENGTH

    fun parse(text: CharSequence, limits: XmlLimits = XmlLimits()): XmlElement = try {
        io.github.yuroyami.kiteplayer.subtitle.xml.XmlMini.parse(text, limits)
    } catch (refusal: XmlSyntaxException) {
        throw XmlException(refusal.reason, refusal.offset)
    }
}
