package io.github.yuroyami.kiteplayer.network.xml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * SEC-6: `parseElement` recursed once per nesting level, so a deep manifest raised
 * `StackOverflowError`. That is an `Error`, so every `catch (Exception)` in this module missed it
 * and the player crashed instead of refusing.
 */
class XmlMiniDepthTest {

    private fun nested(levels: Int): String =
        "<r>" + "<a>".repeat(levels) + "</a>".repeat(levels) + "</r>"

    @Test
    fun `a document nested past the ceiling is refused typed rather than overflowing the stack`() {
        val refusal = assertFailsWith<XmlException> { XmlMini.parse(nested(XmlMini.MAX_DEPTH + 50)) }
        assertTrue("nested past" in refusal.message!!, refusal.message!!)
    }

    @Test
    fun `a document at the ceiling still parses`() {
        // The root counts as one level, so MAX_DEPTH - 1 nested children is the deepest legal doc.
        val root = XmlMini.parse(nested(XmlMini.MAX_DEPTH - 1))
        assertEquals("r", root.name)
    }

    @Test
    fun `the depth counter unwinds so siblings do not accumulate it`() {
        // 300 siblings, each only two deep. A counter that never came back down would refuse this.
        val siblings = "<r>" + "<a><b/></a>".repeat(300) + "</r>"
        assertEquals(300, XmlMini.parse(siblings).children("a").size)
    }
}
