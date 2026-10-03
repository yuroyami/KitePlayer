package io.github.yuroyami.kiteplayer.network.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The order of text and elements inside an element, which TTML needs and a manifest never does (#402). */
class XmlMiniContentTest {

    @Test
    fun mixedContentKeepsItsOrderWhenAsked() {
        val p = XmlMini.parse(
            "<p begin=\"1s\">Hello <span style=\"s1\">big &amp; bold</span><br/>second <![CDATA[<line>]]></p>",
            XmlMini.Limits(keepContent = true),
        )
        val content = p.content
        assertEquals(5, content.size, content.toString())
        assertEquals("Hello ", assertIs<XmlText>(content[0]).value)
        val span = assertIs<XmlElement>(content[1])
        assertEquals("span", span.name)
        assertEquals("big & bold", assertIs<XmlText>(span.content.single()).value)
        assertEquals("br", assertIs<XmlElement>(content[2]).name)
        assertEquals("second ", assertIs<XmlText>(content[3]).value)
        assertEquals("<line>", assertIs<XmlText>(content[4]).value)
        // The element view is the same as without the option.
        assertEquals(listOf("span", "br"), p.children.map { it.name })
        assertEquals("Hello second <line>", p.text)
    }

    @Test
    fun aManifestParseKeepsNoContent() {
        val root = XmlMini.parse("<MPD><Period>text <AdaptationSet/></Period></MPD>")
        assertTrue(root.content.isEmpty())
        assertTrue(root.children.single().content.isEmpty())
    }
}
