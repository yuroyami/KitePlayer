package io.github.yuroyami.kiteplayer.ffmpeg

import io.github.yuroyami.kiteplayer.MediaItem
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The meter against the reference implementation everyone else trusts: the host ffmpeg's
 * ebur128 filter on a real file. The pure-Kotlin meter has its own tests against the standard's
 * reference tones; this is the one that says a whole decoded file agrees with FFmpeg's reading.
 */
class LoudnessOracleTest {

    @Test
    fun `a real file measures within half an LU of ffmpeg's ebur128`() = runBlocking {
        val dir = System.getenv("KITEPLAYER_TESTMEDIA") ?: "testmedia"
        val file = File(dir, "audio-flac.flac")
        if (!file.isFile) return@runBlocking println("SKIP: no ${file.path}; run scripts/testmedia.sh")
        val oracle = ffmpegIntegratedLufs(file) ?: return@runBlocking println("SKIP: no ffmpeg on PATH")

        val measured = AudioAnalysis.measureLoudness(MediaItem(file.absolutePath))

        assertTrue(
            abs(measured.integratedLufs - oracle) <= 0.5,
            "measured ${measured.integratedLufs} LUFS, ffmpeg's ebur128 says $oracle",
        )
        assertTrue(measured.blocksMeasured > 0, "a real file has blocks that survive the gates")
        assertTrue(measured.samplePeak > 0f && measured.samplePeak <= 1.0f, "peak ${measured.samplePeak}")
    }

    /** The `I:` line of `ffmpeg -af ebur128`, or null when there is no ffmpeg to ask. */
    private fun ffmpegIntegratedLufs(file: File): Double? {
        val process = try {
            ProcessBuilder("ffmpeg", "-nostats", "-i", file.absolutePath, "-af", "ebur128", "-f", "null", "-")
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return null
        }
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        val line = output.lineSequence().map { it.trim() }
            .lastOrNull { it.startsWith("I:") && it.endsWith("LUFS") } ?: return null
        return line.removePrefix("I:").removeSuffix("LUFS").trim().toDoubleOrNull()
    }
}
