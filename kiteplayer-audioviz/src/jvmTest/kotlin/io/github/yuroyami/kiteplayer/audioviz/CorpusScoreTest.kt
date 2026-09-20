package io.github.yuroyami.kiteplayer.audioviz

import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Scores the detectors against annotated music, when there is any to score against.
 *
 * The standard asks for a held-out corpus with its provenance, licence and annotations pinned
 * before anything is scored or tuned. This is the harness for that corpus, not the corpus: it
 * skips, loudly, when no clips are present, and it never fails on the numbers. Tuning a threshold
 * until this run looks good would turn the held-out set into a development set, which is the one
 * thing the standard says not to do.
 *
 * Put clips in `kiteplayer-audioviz/corpus`, or point AUDIOVIZ_CORPUS at a folder:
 *
 * - `name.wav`, any sample rate, mono or stereo.
 * - `name.beats.txt`, one beat time in seconds per line.
 * - `name.onsets.txt`, one onset time in seconds per line. Optional.
 * - `name.notes.txt`, free text: where the clip came from, its licence, who annotated it, and
 *   which category it belongs to. Optional for the run, required before anyone quotes a number.
 *
 * What it reports, per clip and over the set: onset precision, recall and F1 at 70 ms, the signed
 * median and the spread of the onset times, beat F1 at 70 ms, and how long the tempo took to
 * become usable. Downbeats are not scored, because nothing here infers a downbeat.
 */
class CorpusScoreTest {

    @Test
    fun scoreTheCorpus() {
        val folder = folder()
        if (folder == null) {
            println(
                "SKIP: no corpus. Put clips in kiteplayer-audioviz/corpus or set AUDIOVIZ_CORPUS.\n" +
                    "      Each clip is name.wav with name.beats.txt, and name.onsets.txt if it has one.",
            )
            return
        }
        val clips = folder.listFiles { file: File -> file.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
        assertTrue(clips.isNotEmpty(), "corpus folder ${folder.absolutePath} holds no wav clips")
        println("corpus at ${folder.absolutePath}, ${clips.size} clips")
        println("clip                      onsets P/R/F1   median ms   beats F1   tempo usable")
        var onsetF1 = 0f
        var beatF1 = 0f
        var scoredOnsets = 0
        var scoredBeats = 0
        for (clip in clips) {
            val name = clip.name.removeSuffix(".wav")
            val samples = readWav(clip)
            if (samples == null) {
                println("  ${name.padEnd(24)} could not be read as 16 bit pcm")
                continue
            }
            val found = detect(samples.first, samples.second)
            val onsets = times(File(folder, "$name.onsets.txt"))
            val beats = times(File(folder, "$name.beats.txt"))
            val onsetScore = onsets?.let { match(it, found.onsets) }
            val beatScore = beats?.let { match(it, found.beats) }
            if (onsetScore != null) { onsetF1 += onsetScore.f1; scoredOnsets++ }
            if (beatScore != null) { beatF1 += beatScore.f1; scoredBeats++ }
            println(
                "  ${name.padEnd(24)} " +
                    (onsetScore?.let { "${round(it.precision)}/${round(it.recall)}/${round(it.f1)}" } ?: "     none") +
                    "   ${onsetScore?.let { round(it.medianMillis) } ?: "  none"}" +
                    "   ${beatScore?.let { round(it.f1) } ?: " none"}" +
                    "   ${round(found.usableAfter)} s",
            )
        }
        if (scoredOnsets > 0) println("onset F1 over ${scoredOnsets} clips: ${round(onsetF1 / scoredOnsets)}")
        if (scoredBeats > 0) println("beat F1 over ${scoredBeats} clips: ${round(beatF1 / scoredBeats)}")
        println(
            "The standard's starting targets are 0.70 live and 0.80 offline at 70 ms. These are " +
                "targets, not a pass mark, and this run never fails on them: a threshold tuned " +
                "until they are met turns held-out music into development music.",
        )
    }

    private class Found(val onsets: List<Float>, val beats: List<Float>, val usableAfter: Float)

    private class Score(val precision: Float, val recall: Float, val f1: Float, val medianMillis: Float)

    /** Runs the analyser over [samples] and collects what it reports. */
    private fun detect(samples: FloatArray, sampleRate: Int): Found {
        val analyzer = SpectrumAnalyzer(sampleRate = sampleRate)
        val onsets = ArrayList<Float>()
        var usableAfter = -1f
        // The tracker reports a rate and a phase rather than a list of beats, so a beat is counted
        // where the phase wraps while the estimate is usable.
        val beats = ArrayList<Float>()
        var lastPhase = -1f
        analyzer.onAnalysis = { frame ->
            frame.detections?.let { batch ->
                for (index in 0 until batch.size) {
                    val detection = batch[index]
                    if (detection.isHit && detection.kind == AudioEventKind.Onset) {
                        onsets += detection.ptsMicros / 1_000_000f
                    }
                }
            }
            val rhythm = frame.rhythm
            if (rhythm != null && rhythm.usable) {
                if (usableAfter < 0f) usableAfter = frame.ptsMicros / 1_000_000f
                val phase = rhythm.beatPhase
                if (lastPhase >= 0f && phase < lastPhase - 0.5f) beats += frame.ptsMicros / 1_000_000f
                lastPhase = phase
            } else {
                lastPhase = -1f
            }
        }
        analyzer.feed(samples, samples.size, 1, 0L)
        return Found(onsets, beats, if (usableAfter < 0f) Float.NaN else usableAfter)
    }

    /** Precision, recall, F1 and the signed median error of [found] against [truth], within 70 ms. */
    private fun match(truth: List<Float>, found: List<Float>): Score {
        if (truth.isEmpty() || found.isEmpty()) return Score(0f, 0f, 0f, Float.NaN)
        val taken = BooleanArray(found.size)
        val errors = ArrayList<Float>()
        var hits = 0
        for (time in truth) {
            var best = -1
            var closest = WINDOW
            for (index in found.indices) {
                if (taken[index]) continue
                val gap = abs(found[index] - time)
                if (gap <= closest) { closest = gap; best = index }
            }
            if (best >= 0) {
                taken[best] = true
                hits++
                errors += (found[best] - time) * 1000f
            }
        }
        val precision = hits.toFloat() / found.size
        val recall = hits.toFloat() / truth.size
        val f1 = if (precision + recall <= 0f) 0f else 2f * precision * recall / (precision + recall)
        val median = if (errors.isEmpty()) Float.NaN else errors.sorted()[errors.size / 2]
        return Score(precision, recall, f1, median)
    }

    private fun folder(): File? {
        val named = System.getenv("AUDIOVIZ_CORPUS")?.let { File(it) }
        if (named != null && named.isDirectory) return named
        val here = File("corpus")
        return if (here.isDirectory) here else null
    }

    private fun times(file: File): List<Float>? =
        if (!file.isFile) null
        else file.readLines().mapNotNull { it.trim().toFloatOrNull() }.sorted()

    /** The clip as mono floats, with its sample rate, or null when it is not 16 bit pcm. */
    private fun readWav(file: File): Pair<FloatArray, Int>? {
        AudioSystem.getAudioInputStream(file).use { stream ->
            val format = stream.format
            if (format.sampleSizeInBits != 16 || format.isBigEndian) return null
            val bytes = stream.readAllBytes()
            val channels = format.channels
            val frames = bytes.size / (2 * channels)
            val out = FloatArray(frames)
            var at = 0
            for (frame in 0 until frames) {
                var sum = 0f
                for (channel in 0 until channels) {
                    val low = bytes[at].toInt() and 0xFF
                    val high = bytes[at + 1].toInt()
                    sum += ((high shl 8) or low) / 32768f
                    at += 2
                }
                out[frame] = sum / channels
            }
            return out to format.sampleRate.toInt()
        }
    }

    private fun round(value: Float): String =
        if (value.isNaN()) "  none" else ((value * 100).toInt() / 100f).toString().padStart(6)

    private companion object {
        /** How close a report has to be to count, in seconds. The standard's 70 ms. */
        const val WINDOW = 0.07f
    }
}
