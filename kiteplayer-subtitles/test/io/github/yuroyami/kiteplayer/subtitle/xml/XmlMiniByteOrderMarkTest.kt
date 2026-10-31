package io.github.yuroyami.kiteplayer.subtitle.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A UTF-8 document may begin with a byte order mark, which XML 1.0 allows (#617). */
class XmlMiniByteOrderMarkTest {

    @Test
    fun aLeadingByteOrderMarkIsSkipped() {
        val plain = XmlMini.parse("<?xml version=\"1.0\"?><MPD type=\"static\"><Period/></MPD>")
        val marked = XmlMini.parse("﻿<?xml version=\"1.0\"?><MPD type=\"static\"><Period/></MPD>")
        assertEquals(plain.name, marked.name)
        assertEquals(plain.attributes, marked.attributes)
        assertEquals(plain.children.map { it.name }, marked.children.map { it.name })
    }

    @Test
    fun aMarkBeforeTheRootWithNoPrologIsSkipped() {
        assertEquals("tt", XmlMini.parse("﻿<tt/>").name)
    }

    @Test
    fun onlyOneMarkIsSkippedAndOffsetsStillCountIt() {
        val refusal = assertFailsWith<XmlSyntaxException> { XmlMini.parse("﻿﻿<tt/>") }
        assertEquals(1, refusal.offset, refusal.message)
    }
}
