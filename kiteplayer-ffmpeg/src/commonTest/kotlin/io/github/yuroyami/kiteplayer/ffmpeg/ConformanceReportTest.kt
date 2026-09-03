package io.github.yuroyami.kiteplayer.ffmpeg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The report the matrix leaves behind, as a pure function of its results. */
class ConformanceReportTest {

    private val results = listOf(
        MatrixResult("baseline.mkv", MatrixVerdict.MustPlay, ok = true, outcome = "played 10 frames"),
        MatrixResult("av1.mkv", MatrixVerdict.MustPlay, ok = false, outcome = "no video stream"),
        MatrixResult("torture.mkv", MatrixVerdict.MustSurvive, ok = true, outcome = "refused: typed"),
    )

    @Test
    fun `one row per result plus a header`() {
        val lines = conformanceReport("macos-arm64", results).trim().lines()
        val rows = lines.filter { it.startsWith("| `") }
        assertEquals(results.size, rows.size, "expected one row per result:\n${lines.joinToString("\n")}")
        assertTrue(lines.first().contains("macos-arm64"), "the platform names the report")
        assertTrue(
            lines.any { it.contains("2 of 3") },
            "the report leads with how many rows met their verdict",
        )
    }

    @Test
    fun `a failed row is in the table and says what happened`() {
        val report = conformanceReport("macos-arm64", results)
        val failing = report.lines().single { it.contains("av1.mkv") }
        assertTrue("FAIL" in failing, failing)
        assertTrue("no video stream" in failing, failing)
    }

    @Test
    fun `a pipe in an outcome cannot break the table`() {
        // The outcome is a row's own transcript and an exception message can carry anything.
        val report = conformanceReport(
            "macos-arm64",
            listOf(MatrixResult("x.mkv", MatrixVerdict.MustPlay, ok = false, outcome = "a|b")),
        )
        val row = report.lines().single { it.contains("x.mkv") }
        assertTrue("\\|" in row, "the pipe must be escaped, not left to split the row: $row")
        // Four columns means five separators. Counting them with the escaped ones removed is what
        // says the outcome stayed one cell instead of splitting into two.
        assertEquals(5, row.replace("\\|", "").count { it == '|' }, row)
    }
}
