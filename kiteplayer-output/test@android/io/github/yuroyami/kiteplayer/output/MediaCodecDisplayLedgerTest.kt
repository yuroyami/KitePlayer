package io.github.yuroyami.kiteplayer.output

import kotlin.test.Test
import kotlin.test.assertEquals

class MediaCodecDisplayLedgerTest {

    /** Collects every answer as text, in the order the ledger gave them. */
    private class Answers {
        val log = mutableListOf<String>()
        fun reportFor(name: String) = object : MediaCodecDisplayReport {
            override fun displayed(atNanos: Long) {
                log += "$name shown at $atNanos"
            }

            override fun lost() {
                log += "$name lost"
            }
        }
    }

    @Test
    fun `a rendered callback reports its frame shown at the platform time`() {
        val answers = Answers()
        val ledger = MediaCodecDisplayLedger()
        ledger.released(41_708L, answers.reportFor("a"))

        ledger.rendered(41_708L, atNanos = 7_000L)

        assertEquals(listOf("a shown at 7000"), answers.log)
    }

    @Test
    fun `frames released before the shown one without a callback were lost`() {
        val answers = Answers()
        val ledger = MediaCodecDisplayLedger()
        ledger.released(0L, answers.reportFor("a"))
        ledger.released(41_708L, answers.reportFor("b"))
        ledger.released(83_417L, answers.reportFor("c"))

        ledger.rendered(0L, atNanos = 1L)
        ledger.rendered(83_417L, atNanos = 3L)

        assertEquals(listOf("a shown at 1", "b lost", "c shown at 3"), answers.log)
    }

    @Test
    fun `a callback for a frame the ledger does not hold changes nothing`() {
        val answers = Answers()
        val ledger = MediaCodecDisplayLedger()
        ledger.released(41_708L, answers.reportFor("a"))

        ledger.rendered(999L, atNanos = 5L)
        ledger.rendered(41_708L, atNanos = 6L)

        assertEquals(listOf("a shown at 6"), answers.log)
    }

    @Test
    fun `clearing drops waiting frames without an answer`() {
        val answers = Answers()
        val ledger = MediaCodecDisplayLedger()
        ledger.released(0L, answers.reportFor("before the seek"))

        ledger.clear()
        ledger.released(9_000_000L, answers.reportFor("after the seek"))
        ledger.rendered(9_000_000L, atNanos = 8L)

        assertEquals(listOf("after the seek shown at 8"), answers.log)
    }

    @Test
    fun `each frame is answered once`() {
        val answers = Answers()
        val ledger = MediaCodecDisplayLedger()
        ledger.released(0L, answers.reportFor("a"))
        ledger.released(41_708L, answers.reportFor("b"))

        ledger.rendered(41_708L, atNanos = 2L)
        ledger.rendered(41_708L, atNanos = 3L)
        ledger.rendered(0L, atNanos = 4L)

        assertEquals(listOf("a lost", "b shown at 2"), answers.log)
    }
}
