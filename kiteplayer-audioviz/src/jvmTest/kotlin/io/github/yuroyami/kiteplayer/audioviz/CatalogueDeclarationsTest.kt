package io.github.yuroyami.kiteplayer.audioviz

import io.github.yuroyami.kiteplayer.audioviz.viz.VizCatalog
import io.github.yuroyami.kiteplayer.audioviz.viz.VizNeed
import io.github.yuroyami.kiteplayer.audioviz.viz.VizQualityControl
import io.github.yuroyami.kiteplayer.audioviz.viz.Visualization
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Detail
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.DetailKind
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.Ground
import io.github.yuroyami.kiteplayer.audioviz.viz.ground.GroundKind
import io.github.yuroyami.kiteplayer.audioviz.viz.shader.ShaderPreset
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * What the catalogue declares and what it compiles, without drawing a frame.
 *
 * These two checks used to sit inside the rendering suites, which the gate leaves out because they
 * take twenty five minutes. They read declarations and compile programs, so they run in seconds and
 * belong to the gate.
 */
class CatalogueDeclarationsTest {

    init { useSkiaGraphics() }

    @Test
    fun everyBuiltInDeclaresItsMapping() {
        val failures = ArrayList<String>()
        for (drawing in VizCatalog.create()) {
            val mapping = drawing.mapping
            if (mapping == null) {
                failures += "${drawing.name}: no mapping"
                continue
            }
            if (mapping.drives.isEmpty()) failures += "${drawing.name}: no drives"
            failures += needProblems(drawing, mapping.needs)
            val echo = drawing.trail > 0f || drawing.moodSpec != null
            if (echo && !mapping.quality.contains(VizQualityControl.EchoResolution)) {
                failures += "${drawing.name}: feeds frames back but declares no echo resolution to give up"
            }
        }
        assertTrue(failures.isEmpty(), "declarations that do not match the drawing:\n" + failures.joinToString("\n"))
    }

    private fun needProblems(drawing: Visualization, needs: Set<VizNeed>): List<String> {
        val problems = ArrayList<String>()
        val shader = drawing is ShaderPreset
        if (shader != needs.contains(VizNeed.RuntimeShader)) {
            problems += "${drawing.name}: ${if (shader) "is a shader" else "is not a shader"}, declares $needs"
        }
        val layers = drawing.warp != null || drawing.ground != null || drawing.detail != null
        if (layers && !needs.contains(VizNeed.ShaderLayers)) {
            problems += "${drawing.name}: has a ground, detail or warp but does not declare ShaderLayers"
        }
        val echo = drawing.trail > 0f || drawing.moodSpec != null
        if (echo != needs.contains(VizNeed.EchoBuffer)) {
            problems += "${drawing.name}: ${if (echo) "keeps a trail" else "keeps no trail"}, declares $needs"
        }
        if ((drawing.bloom > 0) != needs.contains(VizNeed.SoftBuffer)) {
            problems += "${drawing.name}: bloom is ${drawing.bloom}, declares $needs"
        }
        return problems
    }

    @Test
    fun everyGroundAndDetailCompiles() {
        val broken = GroundKind.entries.mapNotNull { kind ->
            Ground(kind).compileError(kind)?.let { "ground $kind: $it" }
        } + DetailKind.entries.mapNotNull { kind ->
            Detail(kind).compileError?.let { "detail $kind: $it" }
        }
        assertTrue(broken.isEmpty(), broken.joinToString("\n\n"))
        println("${GroundKind.entries.size} grounds and ${DetailKind.entries.size} details compiled")
    }
}
