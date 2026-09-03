package io.github.yuroyami.kiteplayer.ffmpeg

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Runs the whole 17.5 matrix and prints one line per row, so a green run leaves the transcript
 * the plan's exit criteria quote. Runs on macOS (the debugging baseline), the iOS simulator
 * (S1.e.3) and, since phase W gave KiteFFmpeg's jvm variant its JNI adapter, the desktop JVM.
 */
class FormatMatrixTest {

    @Test
    fun everyMatrixRowMeetsItsVerdict() = runBlocking {
        val mediaDir = formatMatrixMediaDir()
            ?: error("this platform declares no matrix media directory")
        val results = FormatMatrixRunner.runAll(mediaDir)
        results.forEach { println("MATRIX $it") }

        // Written BEFORE the assertion, so a failing run still leaves the table that says which
        // row failed and what it said. A report only produced on success is evidence about the
        // one case that never needed any.
        val platform = conformancePlatformName()
        val written = writeConformanceReport("$platform.md", conformanceReport(platform, results))
        println("MATRIX report ${written ?: "not written on this platform"}")

        val failed = results.filterNot { it.ok }
        assertTrue(
            failed.isEmpty(),
            "matrix rows failed:\n${failed.joinToString("\n")}",
        )
    }
}
