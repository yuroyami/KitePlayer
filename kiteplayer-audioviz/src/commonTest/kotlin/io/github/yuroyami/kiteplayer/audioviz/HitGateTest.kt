package io.github.yuroyami.kiteplayer.audioviz

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What separates a hit from a texture is the detector's support, not how loud it is.
 *
 * The old rule kept a detection whose strength reached half of full height. That threw away the
 * drums of a quiet passage and kept the loud rustle of a texture, which is the wrong way round.
 * These fixtures set the gate. They do not score it: held-out material is separate.
 */
class HitGateTest {

    private val kinds = listOf(
        AudioEventKind.LowTransient, AudioEventKind.BodyTransient, AudioEventKind.HighTransient,
    )

    private fun detect(samples: FloatArray): List<AudioDetection> {
        val analyzer = SpectrumAnalyzer(sampleRate = 48_000)
        val found = ArrayList<AudioDetection>()
        analyzer.onAnalysis = { frame ->
            frame.detections?.let { batch -> for (index in 0 until batch.size) found += batch[index] }
        }
        analyzer.feed(samples, samples.size, 1, 0L)
        return found.filter { it.kind in kinds }
    }

    /** Loud band-limited noise that swells, with no drums in it. */
    private fun texture(seconds: Float): FloatArray {
        val rate = 48_000
        val out = FloatArray((seconds * rate).toInt())
        var noise = 7_717L
        var low = 0f
        var band = 0f
        for (index in out.indices) {
            val time = index.toFloat() / rate
            noise = noise * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            val white = (noise shr 40).toFloat() / 8_388_608f
            low += (white - low) * 0.05f
            band += (low - band) * 0.3f
            out[index] = (band * 6f * (0.6f + 0.4f * sin(2f * PI.toFloat() * 0.7f * time))).coerceIn(-1f, 1f)
        }
        return out
    }

    private fun share(of: List<AudioDetection>): Float =
        if (of.isEmpty()) 0f else of.count { it.isHit }.toFloat() / of.size

    private fun median(values: List<Float>): Float = values.sorted()[values.size / 2]

    @Test
    fun quietDrumsStayHitsAndALoudTextureMostlyDoesNot() {
        val loud = SyntheticSong.drumLoop(8f)
        val quiet = FloatArray(loud.size) { loud[it] * 0.125f }
        val quietDrums = detect(quiet)
        val kept = share(quietDrums)
        assertTrue(kept >= 0.9f, "the gate dropped drums 18 dB down: it kept $kept of them")
        val strength = median(quietDrums.filter { it.isHit }.map { it.strength })
        assertTrue(strength < 0.5f, "these quiet hits should sit below the old strength gate: median $strength")

        // A kick lands on every beat of the loop. Most of them must still be hits.
        val beat = 60.0 / 130.0
        val kicks = quietDrums.filter { it.kind == AudioEventKind.LowTransient && it.isHit }
        var grid = 0
        var beats = 0
        while (beats * beat < 8.0) {
            val target = (beats * beat * 1_000_000.0).toLong()
            if (kicks.any { abs(it.ptsMicros - target) < 60_000L }) grid++
            beats++
        }
        assertTrue(grid >= beats - 3, "only $grid of $beats quiet kicks were hits")

        val noise = detect(texture(8f))
        assertTrue(noise.size >= 20, "the texture fixture produced only ${noise.size} detections to judge")
        val leaked = share(noise)
        assertTrue(leaked <= 0.4f, "the gate kept $leaked of a loud texture's detections")
        val oldRule = noise.count { it.strength >= 0.5f }.toFloat() / noise.size
        assertTrue(
            leaked < oldRule,
            "the confidence gate must refuse more of this texture than the old strength gate did: " +
                "$leaked against $oldRule",
        )

        assertTrue(detect(SyntheticSong.calmPad(8f)).isEmpty(), "a soft pad fires no transient detector")
    }

    @Test
    fun aPlainDrumLoopReachesASupportedPulse() {
        // Ten drawings spawn something on the edge of a visual cycle, and now wait for a supported
        // pulse before they do. That rule is only reasonable if ordinary music reaches one, so
        // this says when the fixture does.
        val player = SongPlayer(SyntheticSong.drumLoop(16f), warmupSeconds = 0f)
        var supportedAt = -1f
        var step = 0
        while (step < 16 * 60) {
            val frame = player.next(1f / 60f)
            if (supportedAt < 0f && frame.rhythm?.usable == true) supportedAt = step / 60f
            step++
        }
        println("the drum loop reached a supported pulse after $supportedAt s")
        assertTrue(supportedAt in 0f..10f, "the fixture never reached a supported pulse in ten seconds")
    }

    @Test
    fun strengthFollowsTheLevelAndConfidenceDoesNot() {
        val loud = SyntheticSong.drumLoop(8f)
        val quiet = FloatArray(loud.size) { loud[it] * 0.125f }
        val hardAll = detect(loud)
        val softAll = detect(quiet)
        for (kind in kinds) {
            val hard = hardAll.filter { it.kind == kind && it.isHit }
            val soft = softAll.filter { it.kind == kind && it.isHit }
            assertTrue(hard.isNotEmpty() && soft.isNotEmpty(), "$kind: nothing to compare")
            val hardStrength = median(hard.map { it.strength })
            val softStrength = median(soft.map { it.strength })
            assertTrue(
                softStrength < hardStrength * 0.8f,
                "$kind: 18 dB quieter must read lower: $softStrength against $hardStrength",
            )
            assertTrue(
                median(soft.map { it.confidence }) >= median(hard.map { it.confidence }) * 0.9f,
                "$kind: support should survive the level drop",
            )
        }
    }
}
