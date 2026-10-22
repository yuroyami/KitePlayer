package io.github.yuroyami.kiteplayer

import kotlin.test.Test
import kotlin.test.assertEquals

class ChromeTraceFormatTest {

    @Test
    fun `a span becomes a complete event with its times in microseconds`() {
        assertEquals(
            """{"name":"keyframe","cat":"seek","ph":"X","ts":1500.000,"dur":1750.500,"pid":1,"tid":2,"args":{"target":"2000000"}}""",
            ChromeTraceFormat.span(1, 2, "seek", "keyframe", 1_500_000, 3_250_500, mapOf("target" to "2000000")),
        )
    }

    @Test
    fun `an instant becomes an event scoped to its thread`() {
        assertEquals(
            """{"name":"underrun","cat":"audio","ph":"i","s":"t","ts":42.007,"pid":3,"tid":4,"args":{}}""",
            ChromeTraceFormat.instant(3, 4, "audio", "underrun", 42_007, emptyMap()),
        )
    }

    @Test
    fun `names and values are escaped as JSON strings`() {
        assertEquals(
            """{"name":"a\"b","cat":"c\\d","ph":"i","s":"t","ts":0.000,"pid":0,"tid":0,"args":{"line\nbreak":"tab\there\u0001"}}""",
            ChromeTraceFormat.instant(0, 0, "c\\d", "a\"b", 0, mapOf("line\nbreak" to "tab\there\u0001")),
        )
    }
}
