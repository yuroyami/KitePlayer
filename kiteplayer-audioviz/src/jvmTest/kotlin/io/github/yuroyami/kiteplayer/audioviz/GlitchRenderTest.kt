package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.RenderHarness.toBufferedImage
import io.github.yuroyami.kiteplayer.audioviz.viz.VisualizerSurface
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.drawComposedFrame
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Glitch
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Raw geometry comparisons and actual Compose finishing-path captures, all software Skia. */
class GlitchRenderTest {
    init { useSkiaGraphics() }
    private val directory = File("build/glitch-preview").apply { mkdirs() }

    @Test fun fixedCameraDistinguishesFrequencyShapeAndQuietFromBusyMusic() {
        val rendered = (-1..3).map { region ->
            val drawing = fixedGlitch(composition = 1)
            val target = GlitchCanvas(480, 270)
            repeat(90) { target.draw(drawing, glitchReading(it, region = region)) }
            target.snapshot().also {
                assertVisible(it)
                val name = listOf("quiet", "low", "body", "air", "busy")[region + 1]
                ImageIO.write(it, "png", File(directory, "spectrum-$name.png"))
            }
        }
        // All three region fixtures have identical overall level and integrated low/body/air
        // drivers. Only the location of their band energy differs.
        for (a in 1..3) for (b in a + 1..3) {
            assertTrue(glitchChangedFraction(rendered[a], rendered[b]) > 0.005,
                "Separate spectral regions must change visible geometry with travel and rotation stopped: $a / $b")
        }
        assertTrue(glitchChangedFraction(rendered[0], rendered[4]) > 0.05,
            "Silence and busy music must not resolve to the same collage")
        assertTrue(glitchMeanLight(rendered[0]) < glitchMeanLight(rendered[4]),
            "Silence settles to a dimmer recognizable composition")
    }

    @Test fun heldPixelsStayExactWhileLocalControlsWorkAtTheSameInstant() {
        val drawing = fixedGlitch(composition = 1)
        val target = GlitchCanvas(480, 270)
        repeat(90) { target.draw(drawing, glitchReading(it)) }
        val held = glitchReading(90, held = true)
        target.draw(drawing, held)
        val original = target.snapshot()
        repeat(12) {
            target.draw(drawing, held)
            assertContentEquals(glitchPixels(original), glitchPixels(target.snapshot()),
                "Presenting the same paused state cannot advance geometry or noise")
        }
        for ((control, value) in listOf("Composition" to 3f, "Scale" to 0.7f, "Brightness" to 0.45f)) {
            val parameter = drawing.params.single { it.name == control }
            val previous = parameter.value
            parameter.value = value
            target.draw(drawing, held)
            assertTrue(glitchChangedFraction(original, target.snapshot()) > 0.005,
                "$control must redraw an already consumed paused instant")
            parameter.value = previous
            target.draw(drawing, held)
            assertContentEquals(glitchPixels(original), glitchPixels(target.snapshot()),
                "Restoring $control while paused must restore the original pixels")
        }
    }

    @Test fun layerControlsDisableVisibleWorkInTheirManualCompositions() {
        val layers = listOf("Wheel" to 1, "Crystals" to 2, "Ribbons" to 3,
            "Tunnel" to 4, "Eclipse" to 5, "Stars" to 1)
        for ((name, composition) in layers) {
            val drawing = fixedGlitch(composition)
            val target = GlitchCanvas(480, 270)
            val state = glitchReading(0, held = true)
            drawing.params.single { it.name == name }.value = 1f
            target.draw(drawing, state)
            val enabled = target.snapshot()
            drawing.params.single { it.name == name }.value = 0f
            target.draw(drawing, state)
            assertTrue(glitchChangedFraction(enabled, target.snapshot()) > 0.0005,
                "$name must have an independently effective off control")
        }
    }

    @Test fun manualCompositionsAndTheRealPostSurfaceFillPhoneAndDesktop() {
        val width = 480
        val height = 270
        val sheet = BufferedImage(width * 2, (height + 28) * 3, BufferedImage.TYPE_INT_RGB)
        val graphics = sheet.createGraphics()
        graphics.color = Color(12, 12, 18)
        graphics.fillRect(0, 0, sheet.width, sheet.height)
        graphics.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
        val images = (1..5).map { mode ->
            val drawing = fixedGlitch(mode)
            val target = GlitchCanvas(width, height)
            repeat(90) { target.draw(drawing, glitchReading(it)) }
            target.snapshot().also { image ->
                assertVisible(image)
                ImageIO.write(image, "png", File(directory, "composition-$mode-raw.png"))
                val x = (mode - 1) % 2 * width
                val y = (mode - 1) / 2 * (height + 28)
                graphics.drawImage(image, x, y, null)
                graphics.color = Color.WHITE
                graphics.drawString(listOf("Spectrum", "Crystal", "Ribbon", "Tunnel", "Eclipse")[mode - 1], x + 10, y + height + 20)
            }
        }
        graphics.dispose()
        ImageIO.write(sheet, "png", File(directory, "manual-compositions-raw.png"))
        for (a in images.indices) for (b in a + 1 until images.size) {
            assertTrue(glitchChangedFraction(images[a], images[b]) > 0.005,
                "Manual composition ${a + 1} must differ visibly from ${b + 1}")
        }
        for (mode in listOf(0, 2, 3)) for ((w, h) in listOf(540 to 960, 960 to 540)) {
            val drawing = Glitch()
            drawing.params.single { it.name == "Composition" }.value = mode.toFloat()
            val frame = mutableStateOf(glitchReading(0).frame)
            val scene = ImageComposeScene(w, h, Density(1f), content = {
                VisualizerSurface(drawing, { frame.value }, VizPalette.Prism, Modifier.fillMaxSize(),
                    post = true, framesPerSecond = 60)
            })
            try {
                repeat(60) { index ->
                    frame.value = glitchReading(index * 2).frame
                    scene.render(index * 33_333_334L).close()
                }
                frame.value = glitchReading(120).frame
                scene.render(2_000_000_040L).use { image ->
                    val pixels = image.toComposeImageBitmap().toBufferedImage()
                    assertVisible(pixels)
                    val name = when (mode) { 2 -> "crystal-"; 3 -> "ribbon-"; else -> "" }
                    ImageIO.write(pixels, "png", File(directory, "surface-${name}post-${w}x$h.png"))
                }
            } finally {
                scene.close()
            }
        }
    }

    private fun assertVisible(image: BufferedImage) {
        val colours = HashSet<Int>()
        for (y in 0 until image.height step 3) for (x in 0 until image.width step 3) {
            val colour = image.getRGB(x, y)
            assertEquals(255, colour ushr 24, "The complete composition must cover its canvas")
            colours.add(colour)
        }
        assertTrue(colours.size > 32, "Glitch must contain visible drawn detail rather than a flat fill")
    }
}

internal fun fixedGlitch(composition: Int = 3): Glitch = Glitch().also { drawing ->
    for ((name, value) in listOf("Composition" to composition.toFloat(), "Travel" to 0f,
        "Rotation" to 0f, "Scene changes" to 0f)) drawing.params.single { it.name == name }.value = value
}

internal fun glitchReading(index: Int, region: Int = 3, held: Boolean = false): VizRenderState {
    val time = (index + 1f) / 60f
    val quiet = region < 0
    val bands = FloatArray(64) { band ->
        when {
            quiet -> 0f
            region == 3 -> 0.22f + 0.55f * (0.5f + 0.5f * sin(band * 0.34f - time * (1f + band % 4)))
            band * 3 / 64 == region -> 0.8f
            else -> 0.015f
        }
    }
    val level = if (quiet) 0f else 0.6f
    return VizRenderState(SpectrumFrame(
        ptsMicros = ((index + 1L) * 1_000_000L / 60L), bands = bands, peaks = bands.copyOf(),
        scope = FloatArray(128), level = level, bass = level * 0.8f, mid = level * 0.8f,
        treble = level * 0.8f, beat = 0f, pulse = 0f, energy = level,
        density = if (quiet) 0f else 0.65f, mood = if (quiet) 0f else 0.55f,
        loudShort = level, loudLong = level, centroid = 0.5f, held = held,
    ), time, 1f / 60f, VizPalette.Prism, musicTime = time)
}

internal class GlitchCanvas(val width: Int, val height: Int) {
    private val image = ImageBitmap(width, height)
    private val canvas = Canvas(image)
    private val scope = CanvasDrawScope()
    fun draw(drawing: Glitch, state: VizRenderState) {
        scope.draw(Density(1f), LayoutDirection.Ltr, canvas, Size(width.toFloat(), height.toFloat())) {
            drawComposedFrame(drawing, state, null)
        }
    }
    fun snapshot(): BufferedImage = image.toBufferedImage()
}

internal fun glitchPixels(image: BufferedImage): IntArray =
    image.getRGB(0, 0, image.width, image.height, null, 0, image.width)

internal fun glitchChangedFraction(a: BufferedImage, b: BufferedImage): Double {
    require(a.width == b.width && a.height == b.height)
    val first = glitchPixels(a)
    val second = glitchPixels(b)
    val count = first.indices.count { index ->
        (0..2).sumOf { channel ->
            abs((first[index] shr (channel * 8) and 255) - (second[index] shr (channel * 8) and 255))
        } > 18
    }
    return count.toDouble() / first.size
}

internal fun glitchMeanLight(image: BufferedImage): Double = glitchPixels(image).sumOf { pixel ->
    (pixel and 255) + (pixel shr 8 and 255) + (pixel shr 16 and 255)
}.toDouble() / (image.width * image.height * 3 * 255)
