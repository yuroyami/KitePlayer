package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.audioviz.RenderHarness.toBufferedImage
import io.github.yuroyami.kiteplayer.audioviz.viz.VisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Glitch
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/** The actual analyser, audible timeline, event delivery and composition path on software Skia. */
class GlitchMusicTest {
    init { useSkiaGraphics() }
    private val directory = File("build/glitch-preview").apply { mkdirs() }

    @Test fun analysedMusicChangesTheCollageWithTravelAndRotationStopped() {
        val input = sequenceOf("GLITCH_PCM", "FLUCTUS_PCM").mapNotNull { name ->
            System.getenv(name)?.takeIf { it.isNotBlank() }
        }.firstOrNull()
        val samples = if (input == null) SyntheticSong.drumLoop(10f) else readMono(File(input))
        val frames = if (input == null) 180 else 360
        assertTrue(samples.size >= 48_000 * (3f + frames / 60f + 0.25f),
            "Fixture must outlast three seconds of warmup, the capture and analysis lookahead")
        val player = SongPlayer(samples)
        val drawing = Glitch()
        for (name in listOf("Travel", "Rotation")) drawing.params.single { it.name == name }.value = 0f
        val target = GlitchCanvas(640, 360)
        val drawTimes = LongArray(frames)
        val readbackTimes = ArrayList<Long>()
        val capture = if (input == null) null else File(directory, "music").apply { mkdirs() }
        capture?.listFiles { file -> file.name.matches(Regex("[0-9]{4}\\.png")) }?.forEach { it.delete() }
        var musicTime = 0f
        var settled: java.awt.image.BufferedImage? = null
        var maximumChanged = 0.0
        val trace = StringBuilder("frame,audible_seconds,analysis_pts_us,level,bass,mid,treble,density,changed_fraction_from_settled,draw_ms\n")
        for (index in 0 until frames) {
            val frame = player.next(1f / 60f)
            assertTrue(frame.power != null, "The complete capture requires measured spectral input")
            musicTime += frame.motionRate / 60f
            val state = VizRenderState(frame, (index + 1f) / 60f, 1f / 60f,
                VizPalette.Prism, musicTime, player.future)
            val started = System.nanoTime()
            target.draw(drawing, state)
            drawTimes[index] = System.nanoTime() - started
            var changed = Double.NaN
            if (index == 60 || index == frames - 1 || index % 2 == 0) {
                val readback = System.nanoTime()
                val pixels = target.snapshot()
                readbackTimes += System.nanoTime() - readback
                if (index == 60) settled = pixels
                settled?.let { changed = glitchChangedFraction(it, pixels) }
                if (changed.isFinite()) maximumChanged = maxOf(maximumChanged, changed)
                if (capture != null && index % 2 == 0) {
                    ImageIO.write(pixels, "png", File(capture, "%04d.png".format(index / 2)))
                }
                if (index == frames - 1) ImageIO.write(pixels, "png", File(directory, "music-final-raw.png"))
            }
            trace.append("$index,${3.0 + (index + 1) / 60.0},${frame.ptsMicros},${frame.level}," +
                "${frame.bass},${frame.mid},${frame.treble},${frame.density},$changed,${drawTimes[index] / 1_000_000.0}\n")
        }
        val draw = drawTimes.drop(30).sorted()
        val readback = readbackTimes.sorted()
        val p50 = percentile(draw, 0.5)
        val p95 = percentile(draw, 0.95)
        File(directory, "music.csv").writeText(trace.toString())
        File(directory, "cost.txt").writeText(
            "Evidence: JVM software Skia drawComposedFrame, 640x360, 60Hz input cadence.\n" +
                "This is host draw cost, not GPU time or displayed device FPS.\n" +
                "Analysis, pixel readback and PNG encoding are outside the draw timer.\n" +
                "Input: ${input ?: "SyntheticSong.drumLoop(10s)"}\n" +
                "Frames:$frames; warm draw frames excluded:30; draw_p50_ms:$p50; draw_p95_ms:$p95\n" +
                "Readback_p50_ms:${percentile(readback, 0.5)}; readback_p95_ms:${percentile(readback, 0.95)}\n" +
                "Controls:Travel0, Rotation0, remaining defaults. Capture is raw without post effects.\n" +
                "Maximum changed-pixel fraction from settled:$maximumChanged\n" +
                "Optional capture:30fps; first source audio time3.016666667s; duration6s.\n",
        )
        assertTrue(maximumChanged > 0.03,
            "Musical input must visibly alter the composition without camera travel or rotation")
        println("Glitch host draw p50=$p50 ms p95=$p95 ms; readback reported separately; change=$maximumChanged")
    }

    @Test fun defaultMotionFollowsAnalysedMusicThroughCompositionChanges() {
        val input = sequenceOf("GLITCH_PCM", "FLUCTUS_PCM").mapNotNull { name ->
            System.getenv(name)?.takeIf { it.isNotBlank() }
        }.firstOrNull()
        val samples = if (input == null) SyntheticSong.drumLoop(24f) else readMono(File(input))
        val frames = 18 * 60
        assertTrue(samples.size >= 48_000 * (3f + frames / 60f + 0.25f),
            "Normal-motion capture needs at least 21.25 seconds of PCM: warmup, 18 seconds of music and lookahead")
        val player = SongPlayer(samples)
        // These are precisely the selectable preset's defaults, including automatic composition,
        // travel and rotation. Do not replace the musical input with a scheduled scene change.
        val drawing = Glitch()
        val target = GlitchCanvas(640, 360)
        val capture = File(directory, "motion").apply { mkdirs() }
        capture.listFiles { file -> file.name.matches(Regex("[0-9]{4}\\.png")) }?.forEach { it.delete() }
        val postCapture = File(directory, "motion-post").apply { mkdirs() }
        postCapture.listFiles { file -> file.name.matches(Regex("[0-9]{4}\\.png")) }?.forEach { it.delete() }
        // The live Compose renderer owns its own drawing and clocks. Sharing the raw drawing
        // here would advance one state twice and would not represent the actual window path.
        val postDrawing = Glitch()
        val postFrame = mutableStateOf(SpectrumFrame.silent(48, 128))
        val postSurface = ImageComposeScene(640, 360, Density(1f), content = {
            VisualizerSurface(postDrawing, { postFrame.value }, VizPalette.Prism,
                Modifier.fillMaxSize(), future = player.future, post = true, framesPerSecond = 60)
        })
        val drawTimes = LongArray(frames)
        val readbackTimes = ArrayList<Long>()
        val compositions = linkedSetOf<Int>()
        val postCompositions = linkedSetOf<Int>()
        var musicTime = 0f
        var audibleFrames = 0
        val trace = StringBuilder("frame,audible_seconds,analysis_pts_us,level,bass,mid,treble,density," +
            "composition,transitions,accepted_hits,travel,turn,hue,draw_ms\n")
        val postTrace = StringBuilder("frame,audible_seconds,analysis_pts_us,composition,transitions," +
            "accepted_hits,travel,turn,hue,captured\n")
        try {
            // A nonzero initial tick avoids the live frame clock's zero sentinel.
            postSurface.render(1L).close()
            for (index in 0 until frames) {
                val frame = player.next(1f / 60f)
                assertTrue(frame.power != null, "The entire normal-motion capture requires analysed audio")
                if (frame.audible > 0.5f) audibleFrames++
                musicTime += frame.motionRate / 60f
                val state = VizRenderState(frame, (index + 1f) / 60f, 1f / 60f,
                    VizPalette.Prism, musicTime, player.future)
                val started = System.nanoTime()
                target.draw(drawing, state)
                drawTimes[index] = System.nanoTime() - started
                val scene = drawing.scene
                compositions += scene.composition
                trace.append("$index,${3.0 + (index + 1) / 60.0},${frame.ptsMicros},${frame.level}," +
                    "${frame.bass},${frame.mid},${frame.treble},${frame.density},${scene.composition}," +
                    "${scene.transitions},${scene.acceptedHits},${scene.travel},${scene.turn},${scene.hue}," +
                    "${drawTimes[index] / 1_000_000.0}\n")
                if (index % 3 == 0) {
                    val readback = System.nanoTime()
                    val pixels = target.snapshot()
                    readbackTimes += System.nanoTime() - readback
                    ImageIO.write(pixels, "png", File(capture, "%04d.png".format(index / 3)))
                }
                // Feed every actual measured frame at 60Hz; only image encoding is decimated.
                // This entire finishing path remains outside both raw draw and readback timers.
                postFrame.value = frame
                postSurface.render((index + 1L) * 16_666_667L + 1L).use { image ->
                    if (index % 3 == 0) {
                        ImageIO.write(image.toComposeImageBitmap().toBufferedImage(), "png",
                            File(postCapture, "%04d.png".format(index / 3)))
                    }
                }
                val finished = postDrawing.scene
                postCompositions += finished.composition
                postTrace.append("$index,${3.0 + (index + 1) / 60.0},${frame.ptsMicros}," +
                    "${finished.composition},${finished.transitions},${finished.acceptedHits}," +
                    "${finished.travel},${finished.turn},${finished.hue},${index % 3 == 0}\n")
            }
        } finally {
            postSurface.close()
        }
        val draw = drawTimes.drop(30).sorted()
        val readback = readbackTimes.sorted()
        val scene = drawing.scene
        val enoughMusicalEvidence = audibleFrames >= 12 * 60 && scene.acceptedHits >= 24
        File(directory, "motion.csv").writeText(trace.toString())
        File(directory, "motion-post.csv").writeText(postTrace.toString())
        File(directory, "motion-post.txt").writeText(
            "Evidence: actual ImageComposeScene and VisualizerSurface(post=true), JVM software Skia, 640x360.\n" +
                "Separate default Glitch instance, driven by all $frames SongPlayer frames at 60Hz.\n" +
                "Input:${input ?: "SyntheticSong.drumLoop(24s)"}. No synthetic scene-change events.\n" +
                "Finished capture:360 PNGs at20fps; first source audio time3.016666667s; duration18s.\n" +
                "Compositions:${postCompositions.joinToString()}; transitions:${postDrawing.scene.transitions}; " +
                "accepted_hits:${postDrawing.scene.acceptedHits}\n" +
                "Final travel:${postDrawing.scene.travel}; final rotation:${postDrawing.scene.turn}\n" +
                "This path is not timed. The separate motion-cost.txt measures raw drawing only.\n",
        )
        File(directory, "motion-cost.txt").writeText(
            "Evidence: JVM software Skia drawComposedFrame, 640x360, 60Hz analysed input.\n" +
                "This is host draw cost, not GPU time or displayed device FPS.\n" +
                "Analysis, pixel readback and PNG encoding are outside the draw timer.\n" +
                "Input:${input ?: "SyntheticSong.drumLoop(24s)"}; all Glitch controls at defaults.\n" +
                "Frames:$frames; warm draw frames excluded:30; draw_p50_ms:${percentile(draw, 0.5)}; " +
                "draw_p95_ms:${percentile(draw, 0.95)}\n" +
                "Readback_p50_ms:${percentile(readback, 0.5)}; readback_p95_ms:${percentile(readback, 0.95)}\n" +
                "Capture:20fps raw without post effects; first source audio time3.016666667s; duration18s.\n" +
                "Compositions:${compositions.joinToString()}; transitions:${scene.transitions}; accepted_hits:${scene.acceptedHits}\n" +
                "Transition assertion supported by audible duration and accepted musical hits:$enoughMusicalEvidence\n" +
                "Final travel:${scene.travel}; final rotation:${scene.turn}\n",
        )
        if (audibleFrames > 60) {
            assertTrue(scene.travel > 0f && scene.turn > 0f,
                "The default preset must retain visible continuous motion during audible music")
            assertTrue(postDrawing.scene.travel > 0f && postDrawing.scene.turn > 0f,
                "The finished Compose path must retain the default musical motion")
        }
        if (enoughMusicalEvidence) {
            assertTrue(scene.transitions > 0 && compositions.size > 1,
                "Sustained articulated music must reorganize the default composition: " +
                    "hits=${scene.acceptedHits}, transitions=${scene.transitions}, compositions=$compositions")
            assertTrue(postDrawing.scene.transitions > 0 && postCompositions.size > 1,
                "The actual finished Compose capture must include a music-driven composition change: " +
                    "transitions=${postDrawing.scene.transitions}, compositions=$postCompositions")
        }
        println("Glitch default-motion capture:18s, transitions=${scene.transitions}, " +
            "accepted hits=${scene.acceptedHits}, compositions=$compositions; " +
            "host draw p50=${percentile(draw, 0.5)} ms p95=${percentile(draw, 0.95)} ms")
    }

    @Test fun equallyLoudArticulatedNotesChangeThePictureMoreThanAHeldTone() {
        val movement = DoubleArray(2)
        val measuredRms = DoubleArray(2)
        val frequencies = doubleArrayOf(375.0, 750.0, 1_500.0, 3_000.0, 6_000.0)
        for (kind in 0..1) {
            val samples = FloatArray(48_000 * 10) { index ->
                val time = index / 48_000.0
                val frequency = if (kind == 0) 1_500.0 else frequencies[(time * 8).toInt() % frequencies.size]
                val envelope = if (kind == 0) 1.0 else exp(-(time % 0.125) * 16.0)
                (sin(2 * PI * frequency * time) * envelope).toFloat()
            }
            val rms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
            for (index in samples.indices) samples[index] = (samples[index] * 0.035 / rms).toFloat()
            measuredRms[kind] = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
            val player = SongPlayer(samples, referencePower = 0.035 * 0.035)
            val drawing = fixedGlitch(composition = 3)
            val target = GlitchCanvas(320, 180)
            var previous: IntArray? = null
            var comparisons = 0
            for (index in 0 until 240) {
                val frame = player.next(1f / 60f)
                target.draw(drawing, VizRenderState(frame, (index + 1f) / 60f, 1f / 60f,
                    VizPalette.Prism, future = player.future))
                if (index >= 60 && index % 3 == 0) {
                    val pixels = glitchPixels(target.snapshot())
                    previous?.let { before ->
                        movement[kind] += pixelDistance(before, pixels)
                        comparisons++
                    }
                    previous = pixels
                }
            }
            movement[kind] /= comparisons
            ImageIO.write(target.snapshot(), "png", File(directory,
                if (kind == 0) "matched-held-tone.png" else "matched-changing-notes.png"))
        }
        File(directory, "matched-music.csv").writeText("fixture,pcm_rms,mean_pixel_change_per_50ms\n" +
            "held_tone,${measuredRms[0]},${movement[0]}\n" +
            "changing_notes,${measuredRms[1]},${movement[1]}\n")
        assertTrue(abs(measuredRms[0] - measuredRms[1]) < 0.000001,
            "Both fixtures must have the same physical RMS")
        assertTrue(movement[1] > movement[0] * 1.5 && movement[1] > 0.001,
            "Articulated notes must visibly do more than equally loud sustain: ${movement.toList()}")
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Double =
        sorted[((sorted.size - 1) * fraction).toInt()] / 1_000_000.0

    private fun pixelDistance(a: IntArray, b: IntArray): Double {
        var total = 0L
        for (index in a.indices) for (channel in 0..2) {
            val shift = channel * 8
            total += abs((a[index] shr shift and 255) - (b[index] shr shift and 255))
        }
        return total.toDouble() / (a.size * 3 * 255)
    }

    private fun readMono(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size % 4 == 0) { "GLITCH_PCM must contain whole 48kHz mono little-endian float32 samples" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }
}
