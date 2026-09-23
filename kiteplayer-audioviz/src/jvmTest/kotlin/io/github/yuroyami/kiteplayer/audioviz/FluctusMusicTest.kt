package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.RenderHarness.toBufferedImage
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.drawComposedFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Fluctus
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.FluctusSurface
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
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Software Skia evidence through the actual analyser and composition path, not device timing. */
class FluctusMusicTest {
    init { useSkiaGraphics() }

    @Test
    fun composedMusicFramesMoveTheSheetWithTheCameraAndFlowStopped() {
        val input = System.getenv("FLUCTUS_PCM")?.takeIf { it.isNotBlank() }
        val samples = if (input == null) SyntheticSong.drumLoop(10f) else readMono(File(input))
        val frames = if (input == null) 180 else 360
        assertTrue(samples.size >= 48_000 * (3f + frames / 60f + 0.25f),
            "Fixture must cover three seconds of warmup, the capture and the analyser's lookahead")
        val player = SongPlayer(samples)
        val drawing = Fluctus()
        drawing.params.single { it.name == "Flow speed" }.value = 0f
        drawing.params.single { it.name == "Rotation speed" }.value = 0f
        drawing.params.single { it.name == "Wireframe" }.value = 1f
        val directory = File("build/fluctus-preview").apply { mkdirs() }
        val capture = if (input != null) File(directory, "music").apply { mkdirs() } else null
        // Do not leave later frames from an older optional capture in this sequence.
        capture?.listFiles { file -> file.name.matches(Regex("[0-9]{4}\\.png")) }?.forEach { it.delete() }
        val image = ImageBitmap(960, 540)
        val canvas = Canvas(image)
        val scope = CanvasDrawScope()
        val times = LongArray(frames)
        var firstShape: FloatArray? = null
        var largestShapeChange = 0f
        var musicTime = 0f
        var firstPixels: IntArray? = null
        var lastPixels: IntArray? = null
        val trace = StringBuilder("frame,audible_seconds,analysis_pts_us,level,bass,mid,treble,shape_rms_from_settled,draw_ms\n")
        for (index in 0 until frames) {
            val frame = player.next(1f / 60f)
            assertTrue(frame.power != null, "The entire capture must retain actual raw spectral measurements")
            musicTime += frame.motionRate / 60f
            val state = VizRenderState(frame, (index + 1f) / 60f, 1f / 60f,
                VizPalette.Prism, musicTime, player.future)
            val started = System.nanoTime()
            scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(960f, 540f)) {
                drawComposedFrame(drawing, state, null)
            }
            times[index] = System.nanoTime() - started
            if (index == 60) firstShape = drawing.surface.height.copyOf()
            val change = firstShape?.let { rmsDistance(it, drawing.surface.height) } ?: 0f
            largestShapeChange = maxOf(largestShapeChange, change)
            trace.append("$index,${3.0 + (index + 1) / 60.0},${frame.ptsMicros},${frame.level}," +
                "${frame.bass},${frame.mid},${frame.treble},$change,${times[index] / 1_000_000.0}\n")
            // Conversion and image encoding happen after the draw timer stops.
            if ((capture != null && index % 2 == 0) || index == 60 || index == frames - 1) {
                val pixels = image.toBufferedImage()
                if (index == 60) firstPixels = pixels.getRGB(0, 0, 960, 540, null, 0, 960)
                if (index == frames - 1) lastPixels = pixels.getRGB(0, 0, 960, 540, null, 0, 960)
                if (capture != null && index % 2 == 0) {
                    ImageIO.write(pixels, "png", File(capture, "%04d.png".format(index / 2)))
                }
                if (index == frames - 1) ImageIO.write(pixels, "png", File(directory, "fluctus-music-final.png"))
            }
        }
        val measured = times.drop(30).sorted()
        val p50 = measured[measured.size / 2] / 1_000_000.0
        val p95 = measured[((measured.size - 1) * 0.95).toInt()] / 1_000_000.0
        File(directory, "cost.txt").writeText(
            "Evidence: JVM software Skia drawComposedFrame, 960x540, 60Hz input cadence.\n" +
                "This is host draw cost, not GPU time or displayed device FPS.\n" +
                "Analysis, pixel readback and PNG encoding are outside the timed region.\n" +
                "Input: ${input ?: "SyntheticSong.drumLoop(10s)"}\n" +
                "Frames: $frames; warm draw frames excluded:30; p50_ms:$p50; p95_ms:$p95\n" +
                "Controls: Flow speed0, Rotation speed0, Wireframe Surface, remaining defaults.\n" +
                "Maximum settled height RMS change: $largestShapeChange world units.\n" +
                "Optional capture:30fps; first source audio time3.016666667s; duration6s.\n",
        )
        File(directory, "music.csv").writeText(trace.toString())
        assertTrue(largestShapeChange > 0.05f, "The music must visibly deform geometry without camera or field motion")
        val before = checkNotNull(firstPixels)
        val after = checkNotNull(lastPixels)
        assertEquals(before.size, after.size)
        val changed = before.indices.count { i ->
            (0..2).sumOf { channel -> abs((before[i] shr (channel * 8) and 255) - (after[i] shr (channel * 8) and 255)) } > 12
        }
        assertTrue(changed > before.size / 1_000, "Composed output must show the musical deformation")
        println("Fluctus host draw p50=$p50 ms p95=$p95 ms; fixed-camera geometry change=$largestShapeChange")
    }

    @Test
    fun equallyLoudChangingNotesArticulateTheSheetMoreThanAHeldTone() {
        val movement = DoubleArray(2)
        val measuredRms = DoubleArray(2)
        val frequencies = doubleArrayOf(375.0, 750.0, 1_500.0, 3_000.0, 6_000.0)
        for (kind in 0..1) {
            val samples = FloatArray(48_000 * 10) { i ->
                val time = i / 48_000.0
                val note = (time * 8).toInt() % 5
                val frequency = if (kind == 0) 1_500.0 else frequencies[note]
                val envelope = if (kind == 0) 1.0 else exp(-(time % 0.125) * 16.0)
                (sin(2 * PI * frequency * time) * envelope).toFloat()
            }
            val inputRms = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
            for (i in samples.indices) samples[i] = (samples[i] * 0.035 / inputRms).toFloat()
            measuredRms[kind] = sqrt(samples.sumOf { it.toDouble() * it } / samples.size)
            val player = SongPlayer(samples, referencePower = 0.035 * 0.035)
            val surface = FluctusSurface()
            val previous = FloatArray(FluctusSurface.VERTICES)
            for (index in 0 until 240) {
                val state = VizRenderState(player.next(1f / 60f), (index + 1f) / 60f,
                    1f / 60f, VizPalette.Prism)
                surface.advance(state, drift = 0f, rotation = 0f, wireMode = 1)
                if (index > 60) {
                    for (vertex in previous.indices) movement[kind] += abs(surface.height[vertex] - previous[vertex])
                }
                surface.height.copyInto(previous)
            }
            movement[kind] /= 179 * previous.size
        }
        val report = File("build/fluctus-preview/matched-music.csv").apply { parentFile.mkdirs() }
        report.writeText("fixture,pcm_rms,mean_local_height_change_per_frame\n" +
            "held_tone,${measuredRms[0]},${movement[0]}\n" +
            "changing_notes,${measuredRms[1]},${movement[1]}\n")
        assertTrue(abs(measuredRms[0] - measuredRms[1]) < 0.000001, "Both fixtures must have the same physical RMS")
        assertTrue(movement[1] > movement[0] * 3 && movement[1] > 0.002,
            "Changing notes must articulate the surface more than equally loud sustain: ${movement.toList()}")
    }

    private fun readMono(file: File): FloatArray {
        val bytes = file.readBytes()
        require(bytes.size % 4 == 0) { "FLUCTUS_PCM must contain whole little-endian float32 samples" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun rmsDistance(a: FloatArray, b: FloatArray): Float {
        var total = 0.0
        for (i in a.indices) { val delta = (a[i] - b[i]).toDouble(); total += delta * delta }
        return sqrt(total / a.size).toFloat()
    }
}
