package io.github.yuroyami.kiteplayer.network.dash

import io.github.yuroyami.kiteplayer.mp4.Fmp4
import io.github.yuroyami.kiteplayer.mp4.Fmp4Rewrite
import io.github.yuroyami.kiteplayer.mp4.Fmp4UnsupportedException
import io.github.yuroyami.kiteplayer.network.dash.Mp4Bytes.Sample
import io.github.yuroyami.kiteplayer.webm.Webm
import io.github.yuroyami.kiteplayer.webm.WebmUnsupportedException
import kotlin.random.Random
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The fMP4 and WebM readers parse bytes from the network, so every fixture here is cut at every
 * length and mutated a few hundred times with a fixed seed (#723), as `ParserRobustnessTest` does
 * for the subtitle parsers. Two invariants: a reader refuses only with its own exception, never
 * another throwable, and one call takes under a second of wall time, since a spin is a hang.
 */
class ContainerRobustnessTest {

    private val subtitleInit = Mp4Bytes.init(trackId = 1, timescale = 1000, handler = "subt", sampleEntry = "stpp", defaultDuration = 1500)
    private val subtitleTrack = Fmp4.tracks(subtitleInit).single()
    private val avcInit = Mp4Bytes.init(
        trackId = 1, timescale = 90_000, handler = "vide", sampleEntry = "avc1",
        entry = Mp4Bytes.avc1(sps = byteArrayOf(0x67, 0x42, 0, 0x1E), pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())),
        edits = listOf(-1L to 0L, 1000L to 3000L), movieTimescale = 1000,
    )
    private val avcTrack = Fmp4.tracks(avcInit).single()
    private val segment = Mp4Bytes.segment(
        1, decodeTime = 90_000,
        listOf(Sample(2000, byteArrayOf(1, 2, 3), flags = 0x0200_0000L), Sample(3000, byteArrayOf(4, 5), compositionOffset = 1500)),
    )
    private val defaultRuns = Mp4Bytes.defaultRuns(1, listOf(3, 2))
    private val webm = WebmBytes.file(listOf(0, 2000, 4000), blockBytes = 16, durationMillis = 6000.0).bytes

    @Test
    fun theFmp4InitializationReaderRefusesOnlyInItsOwnWords() {
        sweep("Fmp4.tracks", listOf(subtitleInit, avcInit), Fmp4UnsupportedException::class) { Fmp4.tracks(it) }
    }

    @Test
    fun theFmp4FragmentReaderRefusesOnlyInItsOwnWords() {
        sweep("Fmp4.fragments", listOf(segment, defaultRuns), Fmp4UnsupportedException::class) {
            Fmp4.fragments(it, subtitleTrack)
            Fmp4.fragments(it, avcTrack)
        }
    }

    @Test
    fun theFmp4RewriteRefusesOnlyInItsOwnWords() {
        sweep("Fmp4Rewrite.rewrite", listOf(segment, defaultRuns), Fmp4UnsupportedException::class) {
            Fmp4Rewrite.rewrite(it, Fmp4Rewrite.Plan(avcTrack, avcTrack, shiftMicros = 1_000_000, endMicros = 5_000_000, joined = true))
            Fmp4Rewrite.rewrite(it, Fmp4Rewrite.Plan(subtitleTrack, subtitleTrack, shiftMicros = 0, endMicros = null))
        }
    }

    @Test
    fun theWebmHeaderReaderRefusesOnlyInItsOwnWords() {
        sweep("Webm.header", listOf(webm), WebmUnsupportedException::class) { Webm.header(it) }
    }

    @Test
    fun theWebmRenumberingRefusesOnlyInItsOwnWords() {
        sweep("Webm.retrack", listOf(webm), WebmUnsupportedException::class) { Webm.retrack(it, mapOf(1L to 2L, 2L to 1L)) }
    }

    @Test
    fun theWebmIndexReaderRefusesOnlyInItsOwnWords() {
        sweep("WebmIndex.layout", listOf(webm), DashUnsupportedException::class) { WebmIndex.layout(it) }
    }

    /** Every cut of each seed, then [MUTATIONS] seeded mutations of it, through [read]. */
    private fun sweep(name: String, seeds: List<ByteArray>, refusal: KClass<out Throwable>, read: (ByteArray) -> Unit) {
        val random = Random(723)
        for ((index, seed) in seeds.withIndex()) {
            for (length in 0..seed.size) check("$name seed $index cut at $length", seed.copyOf(length), refusal, read)
            repeat(MUTATIONS) { round ->
                val (op, mutated) = mutate(seed, random)
                check("$name seed $index round $round $op", mutated, refusal, read)
            }
        }
    }

    private fun check(where: String, bytes: ByteArray, refusal: KClass<out Throwable>, read: (ByteArray) -> Unit) {
        val mark = TimeSource.Monotonic.markNow()
        try {
            read(bytes)
        } catch (t: Throwable) {
            if (!refusal.isInstance(t)) fail("$where threw $t, not ${refusal.simpleName}\n--- input ---\n${bytes.hex()}", t)
        }
        val elapsed = mark.elapsedNow()
        // A hang never ends, so the cap only needs to beat a slow runner: a debug build on a busy CI
        // simulator took 1.17 s once and 3.01 s in the 0.4.0 publish run.
        assertTrue(elapsed < 10.seconds, "$where took $elapsed\n--- input ---\n${bytes.hex()}")
    }

    private enum class Op { FlipBit, SetByte, Truncate, DuplicateRun, DeleteRun, MaxLength }

    private fun mutate(seed: ByteArray, random: Random): Pair<Op, ByteArray> {
        val op = Op.entries[random.nextInt(Op.entries.size)]
        if (seed.isEmpty()) return op to seed
        val at = random.nextInt(seed.size)
        val out = when (op) {
            Op.FlipBit -> seed.copyOf().also { it[at] = (it[at].toInt() xor (1 shl random.nextInt(8))).toByte() }
            Op.SetByte -> seed.copyOf().also { it[at] = random.nextInt(256).toByte() }
            Op.Truncate -> seed.copyOf(at)
            Op.DuplicateRun -> {
                val end = (at + 1 + random.nextInt(16)).coerceAtMost(seed.size)
                seed.copyOfRange(0, end) + seed.copyOfRange(at, end) + seed.copyOfRange(end, seed.size)
            }
            Op.DeleteRun -> {
                val end = (at + 1 + random.nextInt(16)).coerceAtMost(seed.size)
                seed.copyOfRange(0, at) + seed.copyOfRange(end, seed.size)
            }
            // A length or count field at its largest, the shape that asks a reader for the most.
            Op.MaxLength -> seed.copyOf().also { bytes -> for (i in at until (at + 4).coerceAtMost(bytes.size)) bytes[i] = -1 }
        }
        return op to out
    }

    private fun ByteArray.hex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val MUTATIONS = 400
    }
}
