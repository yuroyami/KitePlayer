package io.github.yuroyami.kiteplayer.audioviz

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.github.yuroyami.kiteplayer.audioviz.viz.Rng
import io.github.yuroyami.kiteplayer.audioviz.viz.VizPalette
import io.github.yuroyami.kiteplayer.audioviz.viz.VizRenderState
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.OdysseyCamera
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.OdysseyScene
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.Recipe
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeScreen
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.RecipeTemplate
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderInputs
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderLibrary
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/** Fixed-camera depth checks per template keep colour animation from disguising motionless architecture. */
class OdysseyWorldTest {
    init { useSkiaGraphics() }
    private val width = System.getenv("ODYSSEY_RENDER_WIDTH")?.toIntOrNull()?.coerceIn(96, 1024) ?: 192
    private val height = width * 5 / 8
    private val coarse = ShaderProgram(OdysseyScene.COARSE_SOURCE)
    private val coarseField = ShaderProgram(OdysseyScene.DISTANCE_SOURCE)
    private val pack = ShaderProgram(OdysseyScene.PACK_SOURCE)
    private val scene = ShaderProgram(OdysseyScene.DISTANCE_SOURCE)
    private val refine = ShaderProgram(OdysseyScene.REFINE_SOURCE)
    private val shade = ShaderProgram(ShaderLibrary.HEADER + OdysseyScene.SOURCE)
    private val recipeSteps = FloatArray(Recipe.SLOTS * Recipe.STEPS * 4)
    private val recipeTails = FloatArray(Recipe.SLOTS * 4)

    /** One accepted recipe per template, the same on every run. */
    private fun recipeOf(template: Int): Recipe {
        val random = Rng(2_000L + template)
        repeat(60) {
            val candidate = RecipeTemplate.ALL[template].draw(template, random)
            if (RecipeScreen.judge(candidate, 80f).ok) return candidate
        }
        error("template $template never passed the screen in 60 draws")
    }

    private fun prepare(template: Int, active: Boolean, banked: Boolean = false) {
        val recipe = recipeOf(template)
        val districts = FloatArray(24)
        for (i in 0..5) {
            districts[i * 4] = -96f + 80f * i
            districts[i * 4 + 1] = 80f
            districts[i * 4 + 2] = recipe.safety
            districts[i * 4 + 3] = 0.35f
        }
        val low = if (active) 0.9f else 0.08f
        val body = if (active) 0.85f else 0.12f
        val hit = if (active) 0.8f else 0f
        // Every slot shows the same recipe, so the eye meets the template whichever district it is in.
        for (slot in 0 until Recipe.SLOTS) {
            recipe.pack(recipeSteps, recipeTails, slot, bass = low * 0.85f, hit = hit * 0.85f, mid = body * 0.85f)
        }
        val camera = FloatArray(12)
        OdysseyCamera.write(camera, floatArrayOf(0.6f, 1.464f, 0.504f), 0.65f, if (banked) 0.5f else 0f, if (banked) -0.2f else 0f,
            if (banked) 0.5f else 0f, if (banked) -0.1f else 0f, if (banked) 0.2f else 0f)
        for (program in listOf(coarse, coarseField, scene, refine, shade)) {
            program.uniform("uResolution", width.toFloat(), height.toFloat())
            program.uniforms("uDistricts", districts)
            program.uniforms("uRecipe", recipeSteps)
            program.uniforms("uRecipeTail", recipeTails)
            program.uniform("uRoutePhase", 0.6f, 1.464f, 0.504f)
            program.uniform("uRoute", 0.65f, 0.75f, 1.376382f, 72f)
            program.uniform("uShape", 8f, 0f, 0f, 0f)
            program.uniform("uSound", low, body, low, low)
            program.uniform("uHits", hit, hit, hit, hit)
            program.uniform("uResponse", 0.85f, 1f, 0.85f, 0.8f)
            program.uniform("uFinish", 0.35f, 0.4f, 0.85f, 88f)
            program.uniforms("uCamera", camera)
            program.uniform("uDynamics", 0f, low, hit, low)
            program.uniform("uWave", 10f, hit, 0f, 0f)
            program.uniform("uColour", 0.1f)
            program.uniform("uParticleTravel", 24f)
        }
        coarse.uniform("uResolution", width * 0.25f, height * 0.25f)
        coarseField.uniform("uResolution", width * 0.25f, height * 0.25f)
        coarse.childProgram("uScene", coarseField)
        pack.childProgram("uMarch", coarse)
        refine.childProgram("uScene", scene)
        refine.uniform("uDepthScale", 0.25f)
        val frame = SpectrumFrame(0, FloatArray(40) { 0.6f }, FloatArray(40), FloatArray(256),
            0.6f, 0.6f, 0.5f, 0.7f, 0f, 0f, energy = 0.6f)
        val state = VizRenderState(frame, 1f, 0f, VizPalette.Prism)
        ShaderInputs().apply { update(state); publish(shade, state, width.toFloat(), height.toFloat()) }
        shade.uniform("uExposure", 0.714f)
    }

    private fun render(depth: Boolean, reference: Boolean = false): IntArray {
        refine.uniform("uDepthScale", if (reference) 1f else 0.25f)
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            if (depth) refine.drawPasses(this, pack, if (reference) 0f else width * 0.25f,
                if (reference) 0f else height * 0.25f, emptyList(), "uDepth")
            else shade.drawPasses(this, pack, width * 0.25f, height * 0.25f, listOf(refine), "uDepth")
        }
        val pixels = bitmap.toPixelMap()
        return IntArray(width * height) {
            val c = pixels[it % width, it / width]
            -0x1000000 or ((c.red * 255).roundToInt() shl 16) or ((c.green * 255).roundToInt() shl 8) or (c.blue * 255).roundToInt()
        }
    }

    private fun depth(pixel: Int): Double =
        (((pixel ushr 16) and 127) * 65025.0 + ((pixel ushr 8) and 255) * 255.0 + (pixel and 255)) * 128.0 / 8258175.0

    private fun checkDepth(template: Int) {
        val fast = render(depth = true)
        val reference = render(depth = true, reference = true)
        var hits = 0; var lost = 0; var receded = 0
        for (i in reference.indices) {
            if ((reference[i] and 0x800000) == 0) continue
            hits++
            if ((fast[i] and 0x800000) == 0) {
                if (lost < 3) println("lost at ${i % width},${i / width}: full=${depth(reference[i])} fast=${depth(fast[i])}")
                lost++; continue
            }
            val expected = depth(reference[i])
            val tolerance = maxOf(0.01, expected * 3.0 / (height * 1.376382))
            if (depth(fast[i]) - expected > tolerance) receded++
        }
        println("template $template reference hits=$hits lost=$lost receded=$receded")
        assertTrue(hits > width * height / 10)
        assertTrue(lost < hits * 0.005 && lost + receded < hits * 0.01,
            "The coarse pass lost foreground geometry in template $template")
    }

    @Test
    fun acceleratedSearchRetainsDetailDuringBankedViewsAndDeformations() {
        for (template in RecipeTemplate.ALL.indices) for (banked in listOf(false, true)) {
            prepare(template, active = true, banked = banked)
            checkDepth(template)
        }
    }

    @Test
    fun everyTemplateChangesItsGeometryWithTheMusicAndCavernsSurroundTheEye() {
        val output = File("build/odyssey-worlds").apply { mkdirs() }
        for (template in RecipeTemplate.ALL.indices) {
            val name = RecipeTemplate.ALL[template].name.replace(' ', '-')
            val states = ArrayList<IntArray>()
            for (active in listOf(false, true)) {
                prepare(template, active)
                states += render(depth = true)
                val colours = render(depth = false)
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                image.setRGB(0, 0, width, height, colours, 0, width)
                ImageIO.write(image, "png", File(output, "$name-${if (active) "active" else "quiet"}.png"))
            }
            val moved = states[0].indices.count {
                ((states[0][it] xor states[1][it]) and 0x800000) != 0 || abs(depth(states[0][it]) - depth(states[1][it])) > 0.08
            }.toFloat() / (width * height)
            println("$name camera-locked geometry change: $moved")
            assertTrue(moved > 0.03f, "$name must physically respond, not just change colour")
            if (RecipeTemplate.ALL[template].cavern) {
                for (state in states) {
                    // Judge enclosure at the screen edges. The centre must stay free to reveal
                    // deep passages; requiring every ray to hit nearby would reward a closed tube.
                    for (edge in 0..3) {
                        val indices = state.indices.filter {
                            val x = it % width; val y = it / width
                            when (edge) {
                                0 -> x < width / 5
                                1 -> x >= width * 4 / 5
                                2 -> y < height / 5
                                else -> y >= height * 4 / 5
                            }
                        }
                        val nearby = indices.count { (state[it] and 0x800000) != 0 && depth(state[it]) < 32.0 }
                        val coverage = nearby.toFloat() / indices.size
                        println("$name edge $edge nearby coverage: $coverage")
                        assertTrue(coverage > 0.8f, "A cavern must surround the viewer on all four sides: $name edge $edge = $coverage")
                    }
                }
            }
        }
    }
}
