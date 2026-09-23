package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.Fluctus
import io.github.yuroyami.kiteplayer.audioviz.viz.presets.FluctusSurface
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FluctusTest {
    init { useSkiaGraphics() }

    @Test
    fun catalogueExposesOneIndependentFluctusWithItsOwnControls() {
        val first = VizCatalog.create().filter { it.name == "Fluctus" }
        val second = VizCatalog.create().filter { it.name == "Fluctus" }
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        assertTrue(first.single() is Fluctus)
        val a = first.single() as Fluctus
        val b = second.single() as Fluctus
        assertNotSame(a, b)
        assertNotSame(a.surface, b.surface)
        assertEquals(PostSpec.Off, a.post)
        assertTrue(a.paintsWholeScreen)
        val expected = linkedMapOf(
            "Surface relief" to 1f, "Flow speed" to 1f, "Rotation speed" to 1f,
            "Scale" to 1f, "Camera tilt" to 0f, "Wireframe" to 0f,
            "Shadow strength" to 1f, "Palette blend" to 0f,
        )
        assertEquals(expected, a.params.associate { it.name to it.default })
        assertEquals(listOf("Auto", "Surface", "Wire"), a.params.single { it.name == "Wireframe" }.choices)
        a.params.first().value = a.params.first().max
        assertEquals(1f, b.params.first().value, "Preview and selected drawing must not share mutable controls")
    }

    @Test
    fun lowMiddleAndHighFrequenciesDeformDifferentPartsAtAFixedCamera() {
        fun response(region: Int): FloatArray {
            val surface = FluctusSurface()
            repeat(90) { frame ->
                surface.advance(state(frame, region = region), drift = 0f, rotation = 0f)
            }
            return surface.height.copyOf()
        }
        val quiet = response(-1)
        val regions = (0..2).map(::response)
        regions.forEachIndexed { region, heights ->
            assertTrue(rms(quiet, heights) > 0.001f, "Frequency region $region must deform the sheet")
            assertTrue(quiet.indices.count { abs(quiet[it] - heights[it]) > 0.001f } > 26,
                "Frequency region $region must move a surface area rather than one point")
        }
        for (a in regions.indices) for (b in a + 1 until regions.size) {
            assertTrue(rms(regions[a], regions[b]) > 0.001f,
                "Different spectrum regions must not collapse into the same overall pulse")
        }
    }

    @Test
    fun theShadowAndVisibleSilhouetteFollowTheSameDeformation() {
        fun projected(region: Int): FluctusSurface = FluctusSurface().also { surface ->
            repeat(90) { surface.advance(state(it, region = region), drift = 0f, rotation = 0f) }
            surface.project(960f, 540f)
        }
        val quiet = projected(-1)
        val active = projected(3)
        assertTrue(rms(quiet.projectedX, active.projectedX) + rms(quiet.projectedY, active.projectedY) > 0.05f,
            "The visible sheet must change shape with the spectrum")
        assertTrue(rms(quiet.shadowX, active.shadowX) + rms(quiet.shadowY, active.shadowY) > 0.05f,
            "The cast shadow must follow the deformed mesh rather than stay a fixed ellipse")
        val affected = quiet.height.indices.count { i ->
            abs(quiet.height[i] - active.height[i]) > 0.001f &&
                abs(quiet.shadowX[i] - active.shadowX[i]) + abs(quiet.shadowY[i] - active.shadowY[i]) > 0.05f
        }
        assertTrue(affected > 26, "Changing vertices must also change their corresponding shadow positions")
    }

    @Test
    fun maximumControlsKeepTheGridFiniteAndReuseItsStorage() {
        val surface = FluctusSurface()
        val arrays = listOf(surface.height, surface.worldX, surface.worldY, surface.worldZ,
            surface.projectedX, surface.projectedY, surface.shadowX, surface.shadowY)
        val indices = surface.indices
        assertEquals(2_601, surface.height.size)
        assertEquals(15_000, indices.size)
        repeat(120) { frame ->
            surface.advance(state(frame, region = frame % 4), deformation = 2.5f,
                drift = 2f, rotation = 2f, wireMode = frame % 3)
            surface.project(if (frame % 2 == 0) 1080f else 1920f,
                if (frame % 2 == 0) 2400f else 1080f,
                zoom = if (frame % 2 == 0) 0.6f else 1.4f,
                tiltDegrees = if (frame % 2 == 0) -20f else 20f)
            arrays.forEach { values -> assertTrue(values.all { it.isFinite() }, "Finite projected and world geometry") }
            assertTrue(surface.wireMix.isFinite() && surface.wireMix in 0f..1f)
        }
        val current = listOf(surface.height, surface.worldX, surface.worldY, surface.worldZ,
            surface.projectedX, surface.projectedY, surface.shadowX, surface.shadowY)
        arrays.zip(current).forEach { (before, after) -> assertSame(before, after) }
        assertSame(indices, surface.indices)
        assertTrue(indices.all { it in 0 until 2_601 }, "Every triangle stays inside the bounded vertex array")
    }

    @Test
    fun pauseFreezesAnExistingShapeAndResetReplaysIt() {
        val surface = FluctusSurface()
        repeat(60) { surface.advance(state(it, region = 3)) }
        surface.project(640f, 360f)
        val before = listOf(surface.height, surface.worldX, surface.worldY, surface.worldZ,
            surface.projectedX, surface.projectedY, surface.shadowX, surface.shadowY).map { it.copyOf() }
        repeat(30) { surface.advance(state(60 + it, region = 1, held = true)) }
        surface.project(640f, 360f)
        val after = listOf(surface.height, surface.worldX, surface.worldY, surface.worldZ,
            surface.projectedX, surface.projectedY, surface.shadowX, surface.shadowY)
        before.zip(after).forEach { (a, b) -> assertContentEquals(a, b, "Paused geometry remains identical") }
        surface.reset()
        repeat(60) { surface.advance(state(it, region = 3)) }
        surface.project(640f, 360f)
        before.zip(after).forEach { (a, b) -> assertContentEquals(a, b, "Reset must reproduce the initial sequence") }
    }

    @Test
    fun reducedMotionStopsTravelWithoutLosingAudioShape() {
        val reduced = FluctusSurface()
        val stationary = FluctusSurface()
        repeat(60) {
            reduced.advance(state(it, region = 0))
            stationary.advance(state(it, region = 0))
        }
        val before = reduced.height.copyOf()
        repeat(60) {
            reduced.advance(state(60 + it, region = 2).also { it.motionScale = 0f })
            stationary.advance(state(60 + it, region = 2), drift = 0f, rotation = 0f)
        }
        assertTrue(rms(before, reduced.height) > 0.001f, "Reduced motion preserves live spectral deformation")
        assertTrue(rms(reduced.height, stationary.height) < 0.001f, "Reduced motion stops field drift")
        assertTrue(rms(reduced.worldX, stationary.worldX) < 0.001f, "Reduced motion stops object rotation")
        assertTrue(rms(reduced.worldZ, stationary.worldZ) < 0.001f, "Reduced motion stops object rotation")
    }

    @Test
    fun animationRateDoesNotDependOnDisplayCadence() {
        fun run(rate: Int): FluctusSurface = FluctusSurface().also { surface ->
            repeat(rate * 2) { surface.advance(state(it, rate = rate, region = 3)) }
        }
        val normal = run(60)
        val scale = (normal.height.maxOrNull()!! - normal.height.minOrNull()!!).coerceAtLeast(1f)
        for (rate in listOf(30, 120)) {
            val other = run(rate)
            assertTrue(rms(normal.height, other.height) < scale * 0.08f,
                "$rate Hz must reach the same surface phase after two seconds")
            assertTrue(rms(normal.worldX, other.worldX) < scale * 0.08f,
                "$rate Hz must reach the same rotation after two seconds")
        }
    }

    @Test
    fun nativeMeshRendersPortraitLandscapeAndWireframe() {
        val directory = File("build/fluctus-preview").apply { mkdirs() }
        for ((width, height) in listOf(960 to 540, 540 to 960)) {
            val renders = listOf(-1 to 1, 3 to 1, 3 to 2).map { (region, mode) ->
                val viz = Fluctus()
                viz.params.single { it.name == "Wireframe" }.value = mode.toFloat()
                repeat(90) { viz.surface.advance(state(it, region = region), wireMode = mode) }
                val image = ImageBitmap(width, height)
                val held = state(90, region = region, held = true)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image),
                    Size(width.toFloat(), height.toFloat())) {
                    with(viz) { draw(held); drawFront(held) }
                }
                val pixels = with(RenderHarness) { image.toBufferedImage() }
                val repeated = ImageBitmap(width, height)
                CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(repeated),
                    Size(width.toFloat(), height.toFloat())) {
                    with(viz) { draw(held); drawFront(held) }
                }
                val repeatedPixels = with(RenderHarness) { repeated.toBufferedImage() }
                assertContentEquals(
                    pixels.getRGB(0, 0, width, height, null, 0, width),
                    repeatedPixels.getRGB(0, 0, width, height, null, 0, width),
                    "Repeated paused presentation must retain exactly the same pixels",
                )
                val colours = HashSet<Int>()
                for (y in 0 until height step 3) for (x in 0 until width step 3) {
                    val colour = pixels.getRGB(x, y)
                    assertEquals(255, colour ushr 24, "Whole-screen drawing must cover each sampled pixel")
                    colours.add(colour)
                }
                assertTrue(colours.size > 32, "A visible shaded sheet cannot be a blank or flat fill")
                val label = if (region < 0) "quiet" else if (mode == 2) "wire" else "active"
                ImageIO.write(pixels, "png", File(directory, "fluctus-$label-${width}x$height.png"))
                pixels
            }
            assertTrue(changedPixels(renders[0], renders[1]) > width * height / 500,
                "Music must change the visible mesh at $width x $height")
            assertTrue(changedPixels(renders[1], renders[2]) > width * height / 500,
                "Wireframe control must visibly change the drawing")
        }
    }

    private fun state(frame: Int, rate: Int = 60, region: Int = 3, held: Boolean = false): VizRenderState {
        val bands = FloatArray(48) { band ->
            if (region == 3 || (region >= 0 && band / 16 == region)) 0.8f else 0.02f
        }
        val time = (frame + 1).toFloat() / rate
        return VizRenderState(
            SpectrumFrame(ptsMicros = (time * 1_000_000).toLong(), bands = bands,
                peaks = bands.copyOf(), scope = FloatArray(256), level = 0.6f,
                bass = 0.3f, mid = 0.3f, treble = 0.3f, beat = 0f, pulse = 0f,
                energy = 0.6f, density = 0.5f, mood = 0.5f,
                loudShort = 0.6f, loudLong = 0.6f, held = held),
            timeSeconds = time, deltaSeconds = 1f / rate, palette = VizPalette.Prism,
            musicTime = time,
        )
    }

    private fun rms(a: FloatArray, b: FloatArray): Float {
        assertEquals(a.size, b.size)
        var squared = 0.0
        for (i in a.indices) { val delta = (a[i] - b[i]).toDouble(); squared += delta * delta }
        return sqrt(squared / a.size).toFloat()
    }

    private fun changedPixels(a: BufferedImage, b: BufferedImage): Int {
        var changed = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val left = a.getRGB(x, y)
            val right = b.getRGB(x, y)
            val difference = (0..2).sumOf { abs((left shr (it * 8) and 255) - (right shr (it * 8) and 255)) }
            if (difference > 18) changed++
        }
        return changed
    }
}
