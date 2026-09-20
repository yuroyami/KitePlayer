package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.KitePlayerPlatform
import io.github.yuroyami.kiteplayer.MediaItem
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizDirector
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * How often the director actually changes the drawing on the real sample songs, and why.
 *
 * The director changes only at a Drop, a Breakdown or a Section Boundary whose confidence is at
 * least 0.6, and never twice inside its hold. If those are rare in real music, the director looks
 * broken even though every part of it works. This prints what the detector found and what the
 * director did with it. It never fails; it is here to answer the question with a number.
 */
class DirectorRateProbe {

    private fun songs(): List<File> = sequenceOf(
        File("../kiteplayer-sample-shared/media"),
        File("kiteplayer-sample-shared/media"),
    ).firstOrNull { it.isDirectory }?.listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(".mp3") }
        .sortedBy { it.name }

    @Test
    fun report() = runBlocking {
        val files = songs()
        if (files.isEmpty()) return@runBlocking println("SKIP: no songs")
        val player = KitePlayerPlatform.createOrNull() ?: return@runBlocking println("SKIP: no desktop player")
        val out = StringBuilder()
        try {
            for (file in files) {
                val analyzer = SpectrumAnalyzer(sampleRate = 44_100)
                val director = VizDirector(VizCatalog.create())
                var seconds = 0.0
                var changes = 0
                val kinds = HashMap<String, Int>()
                val confident = HashMap<String, Int>()
                var lastChangeAt = 0.0
                val gaps = ArrayList<Double>()
                var analyses = 0
                var best = 0f

                analyzer.onAnalysis = { frame ->
                    analyses++
                    // The structural detector publishes here. The director reads frame.events,
                    // which only the analysis feed fills, so this counts what it would be given.
                    frame.structure?.let { batch ->
                        for (index in 0 until batch.size) {
                            val d = batch[index]
                            kinds.merge(d.kind.name, 1, Int::plus)
                            if (d.confidence >= 0.6f) confident.merge(d.kind.name, 1, Int::plus)
                            best = maxOf(best, d.confidence)
                        }
                    }
                    val was = director.current.name
                    // The surface advances the director once per drawn frame; one analysis frame
                    // is 10 ms, close enough to a 120 Hz frame for a rate.
                    director.advance(frame, 0.01f)
                    seconds += 0.01
                    if (director.current.name != was) {
                        changes++
                        gaps += seconds - lastChangeAt
                        lastChangeAt = seconds
                    }
                }

                var decoded = 0L
                var rate = 44_100
                player.scanAudio(MediaItem(file.absolutePath), null) { pts, interleaved, frames, format ->
                    rate = format.sampleRate
                    decoded += frames
                    analyzer.feed(interleaved, frames, format, pts.micros)
                }
                val length = decoded.toDouble() / rate
                val line = "%-26s %5.0f s  structure %-46s  confident(>=0.6) %-22s  best %.2f".format(
                    file.name, length, kinds.toSortedMap().toString(), confident.toSortedMap().toString(), best,
                )
                println(line)
                out.appendLine(line)
            }
        } finally {
            player.closeAndAwait()
        }
        File("build/reports").mkdirs()
        File("build/reports/director-rate.txt").writeText(out.toString())
    }

    private companion object {
        val STRUCTURE = setOf("SectionBoundary", "Drop", "Breakdown")
    }
}
