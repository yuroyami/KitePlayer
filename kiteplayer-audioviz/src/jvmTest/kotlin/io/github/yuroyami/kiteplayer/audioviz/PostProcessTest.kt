package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import io.github.yuroyami.kiteplayer.audioviz.viz.PostProcessedBox
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSwitches
import io.github.yuroyami.kiteplayer.audioviz.viz.masked
import io.github.yuroyami.kiteplayer.audioviz.viz.PostSpec
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The finishing pass, rendered through Compose itself rather than through the drawing harness.
 *
 * The other render tests stop before this pass on purpose: they judge the drawings, and a glow laid
 * over everything would blur what they measure. This one judges the glow.
 */
class PostProcessTest {

    private val width = 240
    private val height = 160

    @Test
    fun cachedBloomFollowsNewScenePixelsAndCanBeDisabled() {
        val colour = mutableStateOf(Color.Red)
        val spec = mutableStateOf(PostSpec(bloom = 1f, bloomRadius = 0.06f, threshold = 0.3f, vignette = 0f, grain = 0f, glitch = false, aberration = 0f))
        val scene = ImageComposeScene(width, height, Density(1f), content = {
            PostProcessedBox(spec = { spec.value }, frame = { SpectrumFrame.silent(8, 16) }, modifier = Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.Black)
                    drawCircle(colour.value, 6f, center)
                }
            }
        })
        fun pixels(time: Long) = scene.render(time).use { it.toComposeImageBitmap().toPixelMap() }
        try {
            val red = pixels(0L)[width / 2 + 10, height / 2]
            colour.value = Color.Blue
            val blue = pixels(16_666_667L)[width / 2 + 10, height / 2]
            assertTrue(red.red > 0.02f && red.blue < 0.01f, "the first halo must be red")
            assertTrue(blue.blue > 0.02f && blue.red < 0.01f, "cached filters must use the new blue scene")
            spec.value = PostSpec.Off
            val plain = pixels(33_333_334L)[width / 2 + 10, height / 2]
            assertTrue(plain.red < 0.01f && plain.blue < 0.01f, "disabling post must remove the cached halo")
        } finally {
            scene.close()
        }
    }

    private fun render(spec: PostSpec, fill: Color = Color.Black, dot: Boolean = true): PixelMap {
        val scene = ImageComposeScene(width, height, Density(1f), content = {
            PostProcessedBox(
                spec = { spec },
                frame = { SpectrumFrame.silent(8, 16) },
                modifier = Modifier.fillMaxSize(),
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(fill)
                    if (dot) drawCircle(Color.White, radius = 5f, center = center)
                }
            }
        })
        val pixels = scene.render(0L).toComposeImageBitmap().toPixelMap()
        scene.close()
        return pixels
    }

    /** Average brightness of the pixels between two distances from the middle. */
    private fun ring(pixels: PixelMap, from: Float, to: Float): Float {
        val middleX = width / 2
        val middleY = height / 2
        var total = 0f
        var counted = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val reach = sqrt(((x - middleX) * (x - middleX) + (y - middleY) * (y - middleY)).toFloat())
                if (reach < from || reach > to) continue
                val colour = pixels[x, y]
                total += (colour.red + colour.green + colour.blue) / 3f
                counted++
            }
        }
        return if (counted == 0) 0f else total / counted
    }

    @Test
    fun bloomSpreadsLightAroundBrightThings() {
        val plain = render(PostSpec.Off)
        val glowing = render(PostSpec(bloom = 1f, bloomRadius = 0.05f, threshold = 0.3f, vignette = 0f, grain = 0f))
        val before = ring(plain, 9f, 22f)
        val after = ring(glowing, 9f, 22f)
        println("around a bright dot: $before without the glow, $after with it")
        assertTrue(before < 0.01f, "without the glow the ring round the dot should be black, was $before")
        assertTrue(after > before + 0.01f, "the glow should spill light round the dot, went from $before to $after")
    }

    @Test
    fun bloomFallsOffSmoothly() {
        // A real blur fades evenly with distance. Copies of the picture laid over each other fade in
        // steps instead, with a ring at the edge of every copy.
        val glowing = render(PostSpec(bloom = 1f, bloomRadius = 0.06f, threshold = 0.3f, vignette = 0f, grain = 0f))
        val rings = (0 until 6).map { step -> ring(glowing, 8f + step * 4f, 12f + step * 4f) }
        println("glow by distance from a bright dot: $rings")
        for (step in 1 until rings.size) {
            assertTrue(rings[step] <= rings[step - 1] + 0.002f, "the glow should fade steadily outward: $rings")
        }
        assertTrue(rings.first() > rings.last() + 0.01f, "and it should actually fade: $rings")
    }

    @Test
    fun dimPartsDoNotGlow() {
        // A grey field below the threshold should come through untouched. A glow that lit up
        // everything would just be a brightness control.
        val grey = Color(0.25f, 0.25f, 0.25f)
        val plain = render(PostSpec.Off, fill = grey, dot = false)
        val glowing = render(PostSpec(bloom = 1f, threshold = 0.5f, vignette = 0f, grain = 0f), fill = grey, dot = false)
        val before = ring(plain, 0f, 60f)
        val after = ring(glowing, 0f, 60f)
        println("a dim field: $before without the glow, $after with it")
        assertTrue(kotlin.math.abs(after - before) < 0.02f, "a dim field should not glow, went from $before to $after")
    }

    @Test
    fun theCornersAreDarkerThanTheMiddle() {
        val pixels = render(PostSpec(bloom = 0f, vignette = 0.6f, grain = 0f), fill = Color.White, dot = false)
        val middle = pixels[width / 2, height / 2]
        val corner = pixels[2, 2]
        println("vignette: middle ${middle.red}, corner ${corner.red}")
        assertTrue(corner.red < middle.red - 0.2f, "the corners should be darker, middle ${middle.red} corner ${corner.red}")
    }

    @Test
    fun aDropTearsThePictureAndNothingElseDoes() {
        val spec = PostSpec(bloom = 0f, vignette = 0f, grain = 0f, glitch = true, aberration = 0f)
        val still = renderWithFrame(spec, dropPulse = 0f)
        val plain = renderWithFrame(PostSpec.Off, dropPulse = 0f)
        val torn = renderWithFrame(spec, dropPulse = 1f)
        val changedWithoutADrop = differing(still, plain)
        val changedByADrop = differing(torn, plain)
        println("pixels changed: $changedWithoutADrop with no drop, $changedByADrop right after one")
        assertTrue(changedWithoutADrop == 0, "with no drop the picture must be left alone, $changedWithoutADrop pixels changed")
        assertTrue(changedByADrop > width * height / 100, "a drop should tear the picture, only $changedByADrop pixels changed")
    }

    @Test
    fun theColoursPartTowardsTheEdges() {
        // A white bar near the edge: with the fringe on, its red spills outward and its blue inward,
        // so the pixels just beside it pick up colour. In the middle nothing should move.
        val plain = renderWithFrame(PostSpec.Off, dropPulse = 0f, kick = 1f)
        val fringed = renderWithFrame(PostSpec(bloom = 0f, vignette = 0f, grain = 0f, glitch = false, aberration = 0.02f), dropPulse = 0f, kick = 1f)
        val changed = differing(plain, fringed)
        println("pixels coloured by the fringe: $changed")
        assertTrue(changed > width * height / 200, "a strong fringe on a kick should colour the edges of the bars, changed $changed")
        val middle = fringed[width / 2, height / 2]
        val plainMiddle = plain[width / 2, height / 2]
        assertTrue(kotlin.math.abs(middle.red - plainMiddle.red) < 0.02f, "the very middle should not move")
    }

    @Test
    fun scanlinesDarkenEveryThirdRow() {
        val pixels = render(PostSpec(bloom = 0f, vignette = 0f, grain = 0f, glitch = false, aberration = 0f, scanlines = 1f), fill = Color.White, dot = false)
        val rows = (0 until 6).map { pixels[width / 2, 40 + it].red }
        println("brightness down six rows under scanlines: $rows")
        assertTrue(rows.min() < 0.6f && rows.max() > 0.9f, "some rows should be dark and the rest left alone: $rows")
    }

    @Test
    fun switchesTurnPartsOffEverywhere() {
        val masked = PostSpec.Default.masked(PostSwitches(bloom = false, grain = false, glitch = false))
        assertTrue(masked.bloom == 0f && masked.grain == 0f && !masked.glitch, "switched off parts should be gone: $masked")
        assertTrue(masked.vignette == PostSpec.Default.vignette, "the rest should be left as the drawing asked")
        assertTrue(PostSpec.Default.masked(PostSwitches.All) === PostSpec.Default, "all switches on should change nothing")
    }

    /** A frame with nothing in it except, optionally, a drop that has just landed. */
    private fun renderWithFrame(spec: PostSpec, dropPulse: Float, kick: Float = 0f): PixelMap {
        val frame = SpectrumFrame(
            ptsMicros = 0L,
            bands = FloatArray(8),
            peaks = FloatArray(8),
            scope = FloatArray(16),
            level = 0f,
            bass = 0f,
            mid = 0f,
            treble = 0f,
            beat = 0f,
            pulse = 0f,
            kick = kick,
            dropPulse = dropPulse,
        )
        val scene = ImageComposeScene(width, height, Density(1f), content = {
            PostProcessedBox(spec = { spec }, frame = { frame }, modifier = Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.Black)
                    // Upright white bars, so a sideways tear shows.
                    for (bar in 0 until 6) {
                        drawRect(
                            Color.White,
                            topLeft = androidx.compose.ui.geometry.Offset(20f + bar * 36f, 0f),
                            size = androidx.compose.ui.geometry.Size(10f, size.height),
                        )
                    }
                }
            }
        })
        val pixels = scene.render(0L).toComposeImageBitmap().toPixelMap()
        scene.close()
        return pixels
    }

    private fun differing(left: PixelMap, right: PixelMap): Int {
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val a = left[x, y]
                val b = right[x, y]
                if (kotlin.math.abs(a.red - b.red) + kotlin.math.abs(a.green - b.green) + kotlin.math.abs(a.blue - b.blue) > 0.02f) count++
            }
        }
        return count
    }

    @Test
    fun offLeavesThePictureAlone() {
        val pixels = render(PostSpec.Off, fill = Color(0.4f, 0.4f, 0.4f), dot = false)
        val corner = pixels[2, 2]
        assertTrue(kotlin.math.abs(corner.red - 0.4f) < 0.01f, "with the pass off nothing should change, corner was ${corner.red}")
    }
}
