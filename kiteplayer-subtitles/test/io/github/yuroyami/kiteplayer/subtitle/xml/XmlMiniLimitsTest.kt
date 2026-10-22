package io.github.yuroyami.kiteplayer.subtitle.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * How much work [XmlMini] does for a document, and where it stops.
 *
 * The step counts read the document through [CountingText], which counts every character the
 * parser reads, so they hold on a busy machine as well as an idle one.
 */
class XmlMiniLimitsTest {

    /** Counts every character read through [get], and every character copied out by [subSequence]. */
    private class CountingText(private val text: String) : CharSequence {
        var reads = 0L
            private set

        override val length: Int get() = text.length

        override fun get(index: Int): Char {
            reads++
            return text[index]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence {
            reads += endIndex - startIndex
            return text.subSequence(startIndex, endIndex)
        }

        override fun toString(): String {
            reads += text.length
            return text
        }
    }

    private fun readsFor(xml: String): Long {
        val text = CountingText(xml)
        XmlMini.parse(text)
        return text.reads
    }

    /** Names of 30 characters that all share one hash code, built from the blocks `Aa` and `BB`. */
    private fun collidingNames(count: Int): List<String> = (0 until count).map { i ->
        (0 until 15).joinToString("") { bit -> if ((i shr bit) and 1 == 0) "Aa" else "BB" }
    }

    @Test
    fun ampersandsInTextAreReadAFewTimesEachNotOncePerLaterAmpersand() {
        val xml = "<MPD>" + "&".repeat(20_000) + ";</MPD>"
        val reads = readsFor(xml)
        assertTrue(reads <= 8L * xml.length, "read $reads characters for a document of ${xml.length}")
    }

    @Test
    fun ampersandsInAnAttributeValueAreReadAFewTimesEach() {
        val xml = "<MPD a=\"" + "&".repeat(20_000) + ";\"/>"
        val reads = readsFor(xml)
        assertTrue(reads <= 8L * xml.length, "read $reads characters for a document of ${xml.length}")
    }

    @Test
    fun unfinishedReferencesAreReadAFewTimesEach() {
        for (unit in listOf("&a", "&#", "&#x1", "&amp", "&ampx")) {
            val xml = "<MPD>" + unit.repeat(10_000) + ";</MPD>"
            val reads = readsFor(xml)
            assertTrue(reads <= 8L * xml.length, "'$unit' repeated: read $reads characters for ${xml.length}")
        }
    }

    @Test
    fun referencesStillDecode() {
        val root = XmlMini.parse(
            "<MPD a=\"&amp;&lt;&gt;&quot;&apos;\">&#65;&#x42;&#X43;&#x1F600; &amp;&amp; &#0065;</MPD>",
        )
        assertEquals("&<>\"'", root.attr("a"))
        assertEquals("ABC😀 && A", root.text)
    }

    @Test
    fun referencesThisReaderDoesNotKnowStayLiteral() {
        val cases = listOf(
            "&nbsp;", "&amp", "&;", "&#;", "&#x;", "&#x110000;", "&#xD800;", "&#99999999999;",
            "&amp-x;", "&#+65;", "&&amp;",
        )
        val expected = listOf(
            "&nbsp;", "&amp", "&;", "&#;", "&#x;", "&#x110000;", "&#xD800;", "&#99999999999;",
            "&amp-x;", "&#+65;", "&&",
        )
        for ((case, want) in cases.zip(expected)) {
            assertEquals(want, XmlMini.parse("<MPD a=\"$case\"/>").attr("a"), "attribute $case")
            assertEquals(want, XmlMini.parse("<MPD>$case</MPD>").text, "text $case")
        }
    }

    @Test
    fun anElementWithMoreAttributesThanTheLimitIsRefused() {
        val names = (0 until XmlMini.MAX_ELEMENT_ATTRIBUTES).map { "a$it" }
        val atLimit = "<MPD " + names.joinToString(" ") { "$it=\"x\"" } + "/>"
        assertEquals(XmlMini.MAX_ELEMENT_ATTRIBUTES, XmlMini.parse(atLimit).attributes.size)
        val refusal = assertFailsWith<XmlSyntaxException> { XmlMini.parse(atLimit.dropLast(2) + " over=\"x\"/>") }
        assertTrue("more than ${XmlMini.MAX_ELEMENT_ATTRIBUTES} attributes" in refusal.message.orEmpty(), refusal.message)
    }

    @Test
    fun manyCollidingAttributeNamesStopAtTheLimitBeforeTheMapFillsUp() {
        val names = collidingNames(32_768)
        assertEquals(1, names.map { it.hashCode() }.toSet().size, "the names share one hash code")
        val xml = "<MPD " + names.joinToString(" ") { "$it=\"x\"" } + "/>"
        val refusal = assertFailsWith<XmlSyntaxException> { XmlMini.parse(xml) }
        // Each attribute is 35 characters wide, so the refusal comes at the 129th of 32,768.
        val stoppedAt = "<MPD ".length + 35 * XmlMini.MAX_ELEMENT_ATTRIBUTES
        assertEquals(stoppedAt, refusal.offset, refusal.message)
    }

    @Test
    fun collidingAttributeNamesUpToTheLimitAllReadBack() {
        val names = collidingNames(XmlMini.MAX_ELEMENT_ATTRIBUTES)
        val element = "<E " + names.mapIndexed { i, name -> "$name=\"$i\"" }.joinToString(" ") + "/>"
        val root = XmlMini.parse("<MPD>" + element.repeat(64) + "</MPD>")
        assertEquals(64, root.children.size)
        for (child in root.children) {
            names.forEachIndexed { i, name -> assertEquals("$i", child.attr(name)) }
        }
    }

    @Test
    fun aDocumentWithMoreAttributesInAllThanTheLimitIsRefused() {
        val limits = XmlMini.Limits(maxAttributes = 10)
        assertEquals(5, XmlMini.parse("<MPD>" + "<E a=\"1\" b=\"2\"/>".repeat(5) + "</MPD>", limits).children.size)
        val refusal = assertFailsWith<XmlSyntaxException> {
            XmlMini.parse("<MPD>" + "<E a=\"1\" b=\"2\"/>".repeat(5) + "<E c=\"3\"/></MPD>", limits)
        }
        assertTrue("more than 10 attributes in the document" in refusal.message.orEmpty(), refusal.message)
    }

    @Test
    fun aDocumentWithMoreElementsThanTheLimitIsRefused() {
        val limits = XmlMini.Limits(maxElements = 10)
        assertEquals(9, XmlMini.parse("<MPD>" + "<E/>".repeat(9) + "</MPD>", limits).children.size)
        val refusal = assertFailsWith<XmlSyntaxException> {
            XmlMini.parse("<MPD>" + "<E/>".repeat(10) + "</MPD>", limits)
        }
        assertTrue("more than 10 elements" in refusal.message.orEmpty(), refusal.message)
    }

    @Test
    fun aDocumentLongerThanTheLimitIsRefusedBeforeAnyParsing() {
        val limits = XmlMini.Limits(maxLength = 16)
        assertEquals("01234", XmlMini.parse("<MPD>01234</MPD>", limits).text)
        val text = CountingText("<MPD>0123456789</MPD>")
        assertFailsWith<XmlSyntaxException> { XmlMini.parse(text, limits) }
        assertEquals(0L, text.reads, "no character is read before the length is checked")
    }

    @Test
    fun theDefaultsAreTheDocumentedCeilings() {
        val limits = XmlMini.Limits()
        assertEquals(8 * 1024 * 1024, limits.maxLength)
        assertEquals(128, limits.maxElementAttributes)
        assertEquals(1024 * 1024, limits.maxAttributes)
        assertEquals(256 * 1024, limits.maxElements)
    }
}
