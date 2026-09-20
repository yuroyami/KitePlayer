package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.Pts
import io.github.yuroyami.kiteplayer.TrackId
import io.github.yuroyami.kiteplayer.spi.AudioFormat
import kotlinx.coroutines.runBlocking
import java.io.File
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Where a song scan spends its time, on the sample song: the decode, the analyser alone and the
 * map builder over the same PCM. Prints and writes build/reports/scan-cost.txt; never fails.
 */
class ScanCostProbe {
    private class Block(val pts: Long, val frames: Int, val format: AudioFormat, val samples: FloatArray)

    private fun song(): File? = sequenceOf(
        File("../kiteplayer-sample-shared/media/bad-cat.mp3"),
        File("kiteplayer-sample-shared/media/bad-cat.mp3"),
        File("../testmedia/audio-mp3.mp3"),
    ).firstOrNull { it.isFile }

    private val cpu = ManagementFactory.getThreadMXBean()

    private inline fun timed(label: String, out: StringBuilder, audioSeconds: Double, block: () -> Unit) {
        val wall0 = System.nanoTime()
        val cpu0 = cpu.currentThreadCpuTime
        block()
        val wallMs = (System.nanoTime() - wall0) / 1e6
        val cpuMs = (cpu.currentThreadCpuTime - cpu0) / 1e6
        val line = "%-34s wall %8.0f ms   cpu %8.0f ms   %6.1fx real time (cpu)".format(label, wallMs, cpuMs, audioSeconds * 1000 / cpuMs.coerceAtLeast(1.0))
        println(line)
        out.appendLine(line)
    }

    @Test
    fun report() = runBlocking {
        val file = song() ?: return@runBlocking println("SKIP: no song")
        val player = KitePlayerPlatform.createOrNull() ?: return@runBlocking println("SKIP: no desktop player")
        val out = StringBuilder()
        val blocks = ArrayList<Block>()
        var track = TrackId(-1)
        var audioSeconds = 0.0
        try {
            val wall0 = System.nanoTime()
            val result = player.scanAudio(MediaItem(file.absolutePath), null) { pts, interleaved, frames, format ->
                blocks += Block(pts.micros, frames, format, interleaved.copyOf(frames * format.channels))
            }
            track = result.track
            audioSeconds = blocks.sumOf { it.frames.toLong() }.toDouble() / blocks.first().format.sampleRate
            val line = "%-34s wall %8.0f ms   (%d blocks, %.1f s of audio, %d Hz, %d ch)".format(
                "decode only (scanAudio, no sink work)", (System.nanoTime() - wall0) / 1e6, blocks.size, audioSeconds,
                blocks.first().format.sampleRate, blocks.first().format.channels,
            )
            println(line); out.appendLine(line)
        } finally {
            player.closeAndAwait()
        }

        // Twice each, so the second run is past JIT warm-up.
        repeat(2) { pass ->
            timed("analyser alone, pass ${pass + 1}", out, audioSeconds) {
                val analyzer = SpectrumAnalyzer(sampleRate = blocks.first().format.sampleRate)
                var frames = 0
                analyzer.onAnalysis = { frames++ }
                for (b in blocks) analyzer.feed(b.samples, b.frames, b.format, b.pts)
                out.appendLine("    analyser published $frames spectrum frames (fft ${analyzer.fftSize}, hop ${analyzer.hop})")
            }
            timed("builder (analyser + map), pass ${pass + 1}", out, audioSeconds) {
                val builder = SongMapBuilder(track)
                for (b in blocks) builder.feed(Pts(b.pts), b.samples, b.frames, b.format)
                val map = builder.build(complete = true)
                out.appendLine("    map: complete=${map.complete} structure=${map.structureCount} keys=${map.keyCount} reference=${map.referencePower}")
            }
        }

        // A poor man's profiler: sample this thread's stack every 2 ms during one more builder pass
        // and count which of our own methods sits on top, and which is the deepest of ours in the stack.
        val worker = Thread.currentThread()
        val top = HashMap<String, Int>()
        val inclusive = HashMap<String, Int>()
        var samples = 0
        val sampling = Thread {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val trace = worker.stackTrace
                    val ours = trace.filter { it.className.startsWith("io.github.yuroyami.kiteplayer") }
                    if (ours.isNotEmpty()) {
                        samples++
                        val leaf = ours.first()
                        top.merge("${leaf.className.substringAfterLast('.')}.${leaf.methodName}", 1, Int::plus)
                        ours.map { "${it.className.substringAfterLast('.')}.${it.methodName}" }.toSet()
                            .forEach { inclusive.merge(it, 1, Int::plus) }
                    }
                    Thread.sleep(2)
                }
            } catch (_: InterruptedException) {
            }
        }
        sampling.isDaemon = true
        sampling.start()
        run {
            val builder = SongMapBuilder(track)
            for (b in blocks) builder.feed(Pts(b.pts), b.samples, b.frames, b.format)
            builder.build(complete = true)
        }
        sampling.interrupt()
        sampling.join()
        out.appendLine("profile: $samples samples of the builder pass")
        out.appendLine("  top of stack (self time):")
        top.entries.sortedByDescending { it.value }.take(14).forEach { (name, n) ->
            out.appendLine("    %5.1f%%  %s".format(100.0 * n / samples, name))
        }
        out.appendLine("  anywhere in stack (inclusive):")
        inclusive.entries.sortedByDescending { it.value }.take(14).forEach { (name, n) ->
            out.appendLine("    %5.1f%%  %s".format(100.0 * n / samples, name))
        }
        println(out.toString())
        File("build/reports").mkdirs()
        File("build/reports/scan-cost.txt").writeText(out.toString())
    }
}
