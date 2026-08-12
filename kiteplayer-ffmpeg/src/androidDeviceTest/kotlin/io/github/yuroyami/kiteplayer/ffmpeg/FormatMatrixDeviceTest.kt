package io.github.yuroyami.kiteplayer.ffmpeg

import android.util.Log
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The 17.5 matrix on a real Android runtime (S1.e.4). Same table, same runner; only where the
 * clips live differs. Rows are logged to logcat under the MATRIX tag so the run leaves the
 * transcript the plan's exit criteria quote.
 */
class FormatMatrixDeviceTest {

    @Test
    fun everyMatrixRowMeetsItsVerdict() = runBlocking {
        val mediaDir = formatMatrixMediaDir()
            ?: error("the instrumentation context has no external files directory")
        check(File(mediaDir).isDirectory) {
            "matrix media not found at $mediaDir. Push it first: " +
                "adb push testmedia/. ${mediaDir}/"
        }
        val results = FormatMatrixRunner.runAll(mediaDir)
        results.forEach { Log.i("MATRIX", it.toString()) }
        val failed = results.filterNot { it.ok }
        assertTrue(
            failed.isEmpty(),
            "matrix rows failed:\n${failed.joinToString("\n")}",
        )
    }
}
