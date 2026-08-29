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
        val failed = results.filterNot { it.ok }
        assertTrue(
            failed.isEmpty(),
            "matrix rows failed:\n${failed.joinToString("\n")}",
        )
    }
}
