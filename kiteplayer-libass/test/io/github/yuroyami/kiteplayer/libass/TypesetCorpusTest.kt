package io.github.yuroyami.kiteplayer.libass

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The corpus that the reference tests share stays well formed, so a failure there is about libass
 * and not about a broken fixture. It is also the one concrete test in this source set, which every
 * test task needs to find.
 */
class TypesetCorpusTest {

    @Test
    fun everyEventNamesADeclaredStyleAndEndsAfterItStarts() {
        val styles = TypesetCorpus.header.lines()
            .filter { it.startsWith("Style:") }
            .map { it.removePrefix("Style:").trim().substringBefore(',') }
            .toSet()
        for ((name, lines) in TypesetCorpus.scripts) for (line in lines) {
            val fields = line.removePrefix("Dialogue: ").split(',', limit = 10)
            assertEquals(10, fields.size, "$name: $line")
            assertTrue(fields[3] in styles, "$name names the undeclared style ${fields[3]}")
        }
    }

    @Test
    fun theMatroskaFormKeepsEveryEventInOrderWithAPositiveDuration() {
        for ((name, lines) in TypesetCorpus.scripts) {
            val chunks = TypesetCorpus.chunks(lines)
            assertEquals(lines.indices.toList(), chunks.map { it.readOrder }, name)
            for (chunk in chunks) {
                assertTrue(chunk.durationMillis > 0, "$name: event ${chunk.readOrder} lasts ${chunk.durationMillis} ms")
                // ReadOrder, Layer, Style, Name, MarginL, MarginR, MarginV, Effect, Text.
                assertEquals(9, chunk.payload.split(',', limit = 9).size, "$name: ${chunk.payload}")
            }
        }
    }
}
