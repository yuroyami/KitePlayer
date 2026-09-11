package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderLibrary
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderProgram
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.TransitionBlend
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpFields
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.WarpSpec
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every shader in the library has to compile, and the compiler's own words have to reach a person.
 *
 * A shader that fails to build does not crash anything. It quietly draws nothing, which looks
 * exactly like a drawing that was always going to be dark, so without this the mistake would be
 * found by a user rather than here.
 */
class ShaderCompileTest {

    init { useSkiaGraphics() }

    @Test
    fun theSharedHeaderCompiles() {
        // The header is not a program on its own, so it is given the smallest possible body.
        val program = ShaderProgram(ShaderLibrary.HEADER + "half4 main(float2 p) { return half4(band(0.5)); }")
        assertTrue(program.available, "the shared shader library does not compile:\n${program.error}")
    }

    @Test
    fun everyShaderDrawingCompiles() {
        val shaders = VizCatalog.create().filterIsInstance<ShaderPreset>()
        assertTrue(shaders.isNotEmpty(), "no shader drawings were found in the catalogue")

        val broken = shaders.filter { !it.runs }
        assertTrue(
            broken.isEmpty(),
            "these shaders did not compile:\n" +
                broken.joinToString("\n\n") { "${it.name}:\n${it.compileError}" },
        )
        println("${shaders.size} shader drawings compiled: ${shaders.joinToString { it.name }}")
    }

    @Test
    fun everyWarpFieldCompiles() {
        val fields = mapOf(
            "twist" to WarpFields.TWIST,
            "wells" to WarpFields.WELLS,
            "falling lenses" to WarpFields.FALLING_LENSES,
            "ripple" to WarpFields.RIPPLE,
            "lens" to WarpFields.LENS,
            "flow" to WarpFields.FLOW,
            "fold" to WarpFields.FOLD,
            "julia" to WarpFields.JULIA,
            "julia walk" to WarpFields.JULIA_WALK,
            "spectro" to WarpFields.SPECTRO,
        )
        val broken = fields.filter { (_, field) -> !WarpSpec(field).runner.available }
        assertTrue(
            broken.isEmpty(),
            "these warp fields did not compile:\n" +
                broken.entries.joinToString("\n\n") { "${it.key}:\n${WarpSpec(it.value).runner.error}" },
        )
        println("${fields.size} warp fields compiled: ${fields.keys.joinToString()}")
    }

    @Test
    fun everyWarpingDrawingHasAWorkingField() {
        val warping = VizCatalog.create().filter { it.warp != null }
        assertTrue(warping.isNotEmpty(), "no drawings use a warp")
        val broken = warping.filter { it.warp?.runner?.available != true }
        assertTrue(
            broken.isEmpty(),
            "these drawings ask for a warp that does not compile: " + broken.joinToString { it.name },
        )
        println("${warping.size} drawings warp: ${warping.joinToString { it.name }}")
    }

    @Test
    fun theTransitionBlendCompiles() {
        val blend = TransitionBlend()
        assertTrue(blend.available, "the transition program does not compile:\n${blend.error}")
    }

    @Test
    fun everyUniformTheLibraryPublishesIsReadable() {
        // A shader that reads all of them, which proves the names in the header match the names the
        // preset base class sets. A typo either side is otherwise invisible.
        val readsEverything = """
half4 main(float2 position) {
    float sum = uTime + uMusicTime + uDelta + uLevel + uBass + uMid + uTreble
        + uEnergy + uMood + uDrive + uDensity + uBeat + uPulse + uKick + uSnare + uHat
        + uBpm + uBeatPhase + uBarPhase + uPhrasePhase + uBeatIn
        + uCentroid + uFlatness + uWidth + uKeyHue + uSeed
        + uResolution.x + band(0.5) + scopeAt(0.5) + palette(0.5).r
        + history(0.5, 0.5) + view(float2(1.0)).y;
    return half4(half3(sum), 1.0);
}
"""
        val program = ShaderProgram(ShaderLibrary.HEADER + readsEverything)
        assertTrue(program.available, "the full uniform block does not compile:\n${program.error}")
    }
}
